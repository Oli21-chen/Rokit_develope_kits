#!/usr/bin/env python3
"""Rokid glasses MouseLinkPacket v1 UDP receiver → Windows relative mouse + left click."""

from __future__ import annotations

import argparse
import ctypes
import hashlib
import hmac
import json
import logging
import math
import socket
import struct
import sys
import threading
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

from pynput import keyboard, mouse

try:
    from frame_link import FrameReassembler, decode_chunk
    from hand_tracker import HandTracker
    from landmark_feedback import encode_landmark_feedback
    from pointer_mapper import TouchGatedPointerMapper
    LAPTOP_MODULES_AVAILABLE = True
except ImportError:
    LAPTOP_MODULES_AVAILABLE = False

MAGIC = 0x524B4D31  # RKM1
CONFIG_MAGIC = 0x524B474D  # RKMG
VERSION = 2
VERSION_V1 = 1
BODY_SIZE = 23
BODY_SIZE_V1 = 21
AUTH_TAG_SIZE = 8
PACKET_SIZE = BODY_SIZE + AUTH_TAG_SIZE
CONFIG_BODY_SIZE = 6
CONFIG_PACKET_SIZE = CONFIG_BODY_SIZE + AUTH_TAG_SIZE
FLAG_HEARTBEAT = 1 << 0
FLAG_OUTPUT_ENABLED = 1 << 1
FLAG_HAND_OK = 1 << 2
FLAG_RECENTER = 1 << 3
FLAG_MOTION_PAUSED = 1 << 4
FLAG_CENTER_CURSOR = 1 << 5
FLAG_PRECISION_MODE = 1 << 6
BUTTON_LEFT = 1 << 0
BUTTON_WHEEL_UP = 1 << 1
BUTTON_WHEEL_DOWN = 1 << 2

STRUCT_BODY = struct.Struct("<IBBHIIhhBH")  # v2: + gainMilli u16
STRUCT_BODY_V1 = struct.Struct("<IBBHIIhhB")
STRUCT_CONFIG = struct.Struct("<IH")

# Glasses HUD / virtual control plane (see app CONSTANT.kt).
GLASSES_REF_WIDTH = 480
GLASSES_REF_HEIGHT = 640

TMS_REWIND_RESET_MS = 2_000
GUARD_IDLE_RESET_S = 30.0
LEFT_RELEASE_DEBOUNCE = 4

GAIN_MIN = 0.01
GAIN_MAX = 2.0
GAIN_DEFAULT = 0.25
SETTINGS_FILENAME = ".mouse_agent_settings.json"


@dataclass
class Decoded:
    magic: int
    version: int
    flags: int
    session_id: int
    sequence: int
    t_ms: int
    dx: int
    dy: int
    buttons: int
    auth_valid: bool
    link_gain: float = GAIN_DEFAULT

    @property
    def heartbeat(self) -> bool:
        return bool(self.flags & FLAG_HEARTBEAT)

    @property
    def output_enabled(self) -> bool:
        return bool(self.flags & FLAG_OUTPUT_ENABLED)

    @property
    def hand_ok(self) -> bool:
        return bool(self.flags & FLAG_HAND_OK)

    @property
    def recenter(self) -> bool:
        return bool(self.flags & FLAG_RECENTER)

    @property
    def motion_paused(self) -> bool:
        return bool(self.flags & FLAG_MOTION_PAUSED)

    @property
    def center_cursor(self) -> bool:
        return bool(self.flags & FLAG_CENTER_CURSOR)

    @property
    def precision_mode(self) -> bool:
        return bool(self.flags & FLAG_PRECISION_MODE)

    @property
    def left_pressed(self) -> bool:
        return bool(self.buttons & BUTTON_LEFT)


def hmac_tag(token: str, body: bytes) -> bytes:
    digest = hmac.new(token.encode("utf-8"), body, hashlib.sha256).digest()
    return digest[:AUTH_TAG_SIZE]


def gain_to_milli(gain: float) -> int:
    clamped = max(GAIN_MIN, min(GAIN_MAX, gain))
    return int(round(clamped * 1000.0))


def gain_from_milli(milli: int) -> float:
    return max(GAIN_MIN, min(GAIN_MAX, milli / 1000.0))


def encode_gain_packet(gain: float, token: str) -> bytes:
    body = struct.pack("<IH", CONFIG_MAGIC, gain_to_milli(gain))
    return body + hmac_tag(token, body)


def decode_gain_packet(data: bytes, token: str) -> Optional[float]:
    if len(data) < CONFIG_PACKET_SIZE:
        return None
    body = data[:CONFIG_BODY_SIZE]
    tag = data[CONFIG_BODY_SIZE:CONFIG_PACKET_SIZE]
    if not hmac.compare_digest(tag, hmac_tag(token, body)):
        return None
    magic, gain_milli = STRUCT_CONFIG.unpack(body)
    if magic != CONFIG_MAGIC:
        return None
    return gain_from_milli(gain_milli & 0xFFFF)


def decode_packet(data: bytes, token: str) -> Optional[Decoded]:
    if len(data) < BODY_SIZE_V1 + AUTH_TAG_SIZE:
        return None
    version = data[4]
    body_size = BODY_SIZE if version >= VERSION else BODY_SIZE_V1
    if len(data) < body_size + AUTH_TAG_SIZE:
        return None
    body = data[:body_size]
    tag = data[body_size : body_size + AUTH_TAG_SIZE]
    auth_valid = hmac.compare_digest(tag, hmac_tag(token, body))
    if version >= VERSION:
        magic, version, flags, session_id, sequence, t_ms, dx, dy, buttons, gain_milli = STRUCT_BODY.unpack(body)
        link_gain = gain_from_milli(gain_milli & 0xFFFF)
    else:
        magic, version, flags, session_id, sequence, t_ms, dx, dy, buttons = STRUCT_BODY_V1.unpack(body)
        link_gain = GAIN_DEFAULT
    if magic != MAGIC or version not in (VERSION, VERSION_V1):
        return None
    return Decoded(
        magic=magic,
        version=version,
        flags=flags,
        session_id=session_id & 0xFFFF,
        sequence=sequence & 0xFFFFFFFF,
        t_ms=t_ms & 0xFFFFFFFF,
        dx=dx,
        dy=dy,
        buttons=buttons & 0xFF,
        auth_valid=auth_valid,
        link_gain=link_gain,
    )


def primary_screen_size() -> tuple[int, int]:
    """Primary monitor pixel size (Windows). Falls back to 1920×1080."""
    try:
        user32 = ctypes.windll.user32  # type: ignore[attr-defined]
        width = int(user32.GetSystemMetrics(0))
        height = int(user32.GetSystemMetrics(1))
        if width > 0 and height > 0:
            return width, height
    except Exception:
        pass
    return 1920, 1080


class MouseInjector:
    """Thread-safe relative mouse + left button (SendInput on Windows for reliable drag)."""

    INPUT_MOUSE = 0
    MOUSEEVENTF_MOVE = 0x0001
    MOUSEEVENTF_LEFTDOWN = 0x0002
    MOUSEEVENTF_LEFTUP = 0x0004
    MOUSEEVENTF_WHEEL = 0x0800
    WHEEL_DELTA = 120

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._left_down = False
        self._use_sendinput = sys.platform == "win32"
        self._controller = mouse.Controller()
        self._extra = ctypes.c_ulong(0)
        if self._use_sendinput:

            class _MouseInput(ctypes.Structure):
                _fields_ = [
                    ("dx", ctypes.c_long),
                    ("dy", ctypes.c_long),
                    ("mouseData", ctypes.c_ulong),
                    ("dwFlags", ctypes.c_ulong),
                    ("time", ctypes.c_ulong),
                    ("dwExtraInfo", ctypes.POINTER(ctypes.c_ulong)),
                ]

            class _InputUnion(ctypes.Union):
                _fields_ = [("mi", _MouseInput)]

            class _Input(ctypes.Structure):
                _anonymous_ = ("_data",)
                _fields_ = [
                    ("type", ctypes.c_ulong),
                    ("_data", _InputUnion),
                ]

            self._mouse_input = _MouseInput
            self._input = _Input
            self._send_input = ctypes.windll.user32.SendInput  # type: ignore[attr-defined]
            self._set_cursor_pos = ctypes.windll.user32.SetCursorPos  # type: ignore[attr-defined]

    def left_down(self) -> bool:
        with self._lock:
            return self._left_down

    def _send_mouse(self, flags: int, *, dx: int = 0, dy: int = 0, data: int = 0) -> None:
        inp = self._input(
            type=self.INPUT_MOUSE,
            mi=self._mouse_input(
                dx=dx,
                dy=dy,
                mouseData=data,
                dwFlags=flags,
                time=0,
                dwExtraInfo=ctypes.pointer(self._extra),
            ),
        )
        sent = self._send_input(1, ctypes.byref(inp), ctypes.sizeof(self._input))
        if sent != 1:
            raise OSError(f"SendInput returned {sent}")

    def press_left(self) -> None:
        with self._lock:
            if self._left_down:
                return
            if self._use_sendinput:
                self._send_mouse(self.MOUSEEVENTF_LEFTDOWN)
            else:
                self._controller.press(mouse.Button.left)
            self._left_down = True

    def release_left(self) -> None:
        with self._lock:
            if not self._left_down:
                return
            if self._use_sendinput:
                self._send_mouse(self.MOUSEEVENTF_LEFTUP)
            else:
                self._controller.release(mouse.Button.left)
            self._left_down = False

    def move(self, dx: int, dy: int, *, smooth: bool = False, max_step: int = 5) -> None:
        if not dx and not dy:
            return
        if smooth:
            self._move_smooth(dx, dy, max_step=max_step)
            return
        self._move_once(dx, dy)

    def _move_once(self, dx: int, dy: int) -> None:
        if not dx and not dy:
            return
        with self._lock:
            if self._use_sendinput:
                self._send_mouse(self.MOUSEEVENTF_MOVE, dx=dx, dy=dy)
            else:
                self._controller.move(dx, dy)

    def _move_smooth(self, dx: int, dy: int, *, max_step: int) -> None:
        """Split long drag segments so Paint sees smaller straight pieces (~ smoother curves)."""
        remaining_x = int(dx)
        remaining_y = int(dy)
        while remaining_x or remaining_y:
            if abs(remaining_x) <= max_step and abs(remaining_y) <= max_step:
                self._move_once(remaining_x, remaining_y)
                break
            scale = max_step / float(max(abs(remaining_x), abs(remaining_y), 1))
            step_x = int(round(remaining_x * scale))
            step_y = int(round(remaining_y * scale))
            if step_x == 0 and remaining_x != 0:
                step_x = 1 if remaining_x > 0 else -1
            if step_y == 0 and remaining_y != 0:
                step_y = 1 if remaining_y > 0 else -1
            self._move_once(step_x, step_y)
            remaining_x -= step_x
            remaining_y -= step_y

    def scroll(self, delta: int) -> None:
        if not delta:
            return
        with self._lock:
            if self._use_sendinput:
                self._send_mouse(
                    self.MOUSEEVENTF_WHEEL,
                    data=int(delta * self.WHEEL_DELTA),
                )
            else:
                self._controller.scroll(0, delta)

    def set_position(self, x: int, y: int) -> None:
        with self._lock:
            if self._use_sendinput:
                self._set_cursor_pos(int(x), int(y))
            else:
                self._controller.position = (x, y)


def scale_motion(
    dx: int,
    dy: int,
    *,
    gain: float,
    screen_scale: bool,
    screen_width: int,
    screen_height: int,
) -> tuple[int, int]:
    """Map glasses deltas → laptop pixels using optional 480×640 reference plane."""
    factor_x = gain
    factor_y = gain
    if screen_scale:
        factor_x *= screen_width / GLASSES_REF_WIDTH
        factor_y *= screen_height / GLASSES_REF_HEIGHT
    out_x = int(round(dx * factor_x))
    out_y = int(round(dy * factor_y))
    return out_x, out_y


class SubpixelAccumulator:
    """Retain fractional pointer deltas across frames before screen scaling."""

    def __init__(self) -> None:
        self.x = 0.0
        self.y = 0.0

    def reset(self) -> None:
        self.x = 0.0
        self.y = 0.0

    def add(
        self,
        dx: float,
        dy: float,
        *,
        gain: float,
        screen_scale: bool,
        screen_width: int,
        screen_height: int,
        round_output: bool = False,
    ) -> tuple[int, int]:
        factor_x = gain
        factor_y = gain
        if screen_scale:
            factor_x *= screen_width / GLASSES_REF_WIDTH
            factor_y *= screen_height / GLASSES_REF_HEIGHT
        self.x += dx * factor_x
        self.y += dy * factor_y
        quantize = round if round_output else math.trunc
        move_x = int(quantize(self.x))
        move_y = int(quantize(self.y))
        if move_x:
            self.x -= move_x
        if move_y:
            self.y -= move_y
        return move_x, move_y


class SensitivitySettings:
    """Thread-safe gain value; optionally persisted next to this script."""

    def __init__(self, gain: float, settings_path: Path) -> None:
        self._lock = threading.Lock()
        self._gain = max(GAIN_MIN, min(GAIN_MAX, gain))
        self._settings_path = settings_path

    def get(self) -> float:
        with self._lock:
            return self._gain

    def set(self, gain: float) -> None:
        with self._lock:
            self._gain = max(GAIN_MIN, min(GAIN_MAX, gain))

    def save(self) -> None:
        try:
            payload = {"gain": round(self.get(), 4)}
            self._settings_path.write_text(json.dumps(payload, indent=2), encoding="utf-8")
        except OSError as error:
            logging.warning("Could not save settings: %s", error)

    @classmethod
    def load(cls, settings_path: Path, default_gain: float) -> SensitivitySettings:
        gain = default_gain
        if settings_path.is_file():
            try:
                data = json.loads(settings_path.read_text(encoding="utf-8"))
                gain = float(data.get("gain", default_gain))
            except (OSError, ValueError, TypeError) as error:
                logging.warning("Could not load settings (%s); using default gain", error)
        return cls(gain=gain, settings_path=settings_path)


class ReceiveGuard:
    """Drop replays; reset when glasses reboot (new session or elapsedRealtime rewind)."""

    def __init__(self) -> None:
        self.session_id: Optional[int] = None
        self.last_sequence: Optional[int] = None
        self.last_t_ms: Optional[int] = None
        self.last_accept_mono: float = 0.0

    def reset(self, reason: str) -> None:
        self.session_id = None
        self.last_sequence = None
        self.last_t_ms = None
        logging.info("ReceiveGuard reset (%s)", reason)

    def accept(self, session_id: int, sequence: int, t_ms: int) -> tuple[bool, Optional[str]]:
        now = time.monotonic()
        if self.last_accept_mono > 0 and now - self.last_accept_mono > GUARD_IDLE_RESET_S:
            self.reset(f"idle {GUARD_IDLE_RESET_S:.0f}s")

        if self.session_id is not None and session_id != self.session_id:
            self.reset(f"session {self.session_id} -> {session_id}")

        if self.last_t_ms is not None and t_ms + TMS_REWIND_RESET_MS < self.last_t_ms:
            self.reset(f"clock rewind t_ms={t_ms} last={self.last_t_ms}")

        if self.last_sequence is not None and sequence == self.last_sequence:
            return False, "duplicate_seq"
        if self.last_t_ms is not None and t_ms + 2 < self.last_t_ms:
            return False, "stale_t_ms"

        self.session_id = session_id
        self.last_sequence = sequence
        self.last_t_ms = t_ms
        self.last_accept_mono = now
        return True, None


class MouseAgent:
    def __init__(
        self,
        token: str,
        sensitivity: SensitivitySettings,
        *,
        timeout_ms: int = 200,
        status_interval_s: float = 2.0,
        screen_scale: bool = True,
        screen_width: Optional[int] = None,
        screen_height: Optional[int] = None,
        laptop_inference: bool = False,
        model_path: Optional[Path] = None,
        motion_hz: float = 30.0,
        feedback_port: int = 9462,
    ) -> None:
        self.token = token
        self.sensitivity = sensitivity
        self.timeout_s = timeout_ms / 1000.0
        self.status_interval_s = status_interval_s
        self.screen_scale = screen_scale
        self.screen_width = screen_width or primary_screen_size()[0]
        self.screen_height = screen_height or primary_screen_size()[1]
        self.laptop_inference = laptop_inference and LAPTOP_MODULES_AVAILABLE
        self.motion_period_s = 1.0 / motion_hz if motion_hz > 0 else 0.033
        self._mouse = MouseInjector()
        self.armed = False
        self.last_buttons = 0
        self._os_left_down = False
        self._left_release_streak = 0
        self.last_accept_mono = 0.0
        self.guard = ReceiveGuard()
        self.lock = threading.Lock()
        self._stop = threading.Event()
        self.accept_count = 0
        self.reject_count = 0
        self.timeout_releases = 0
        self.peer_addr: Optional[str] = None
        self.peer_endpoint: Optional[tuple[str, int]] = None
        self.active_session_id: Optional[int] = None
        self.last_decoded: Optional[Decoded] = None
        self._gain_ui_callback: Optional[callable] = None
        self._last_reject_log_mono = 0.0
        self._last_status_log_mono = 0.0
        self.control_output_enabled = False
        self.control_motion_paused = False
        self.frame_accept_count = 0
        self.frame_reject_count = 0
        self.last_inference_ms = 0.0
        self._frame_reassembler = FrameReassembler() if self.laptop_inference else None
        self._pointer_mapper = (
            TouchGatedPointerMapper(use_palm_center=False) if self.laptop_inference else None
        )
        self._hand_tracker: Optional[HandTracker] = None
        if self.laptop_inference and model_path is not None:
            try:
                self._hand_tracker = HandTracker(model_path)
            except FileNotFoundError as error:
                logging.warning("%s — frame port will receive but inference disabled", error)
                self.laptop_inference = False
        self._motion_thread: Optional[threading.Thread] = None
        self._last_motion_dx = 0.0
        self._last_motion_dy = 0.0
        self._last_hand_ok = False
        self._last_inferred_frame_seq: Optional[int] = None
        self._mp_video_ts_ms = 0
        self._frame_session_id: Optional[int] = None
        self._frame_inference_period_ms = max(1, int(round(self.motion_period_s * 1000.0)))
        self.feedback_port = feedback_port
        self._feedback_sock: Optional[socket.socket] = None
        self._feedback_sequence = 0
        self._feedback_peer: Optional[tuple[str, int]] = None
        self.control_precision_mode = False
        self._subpixel = SubpixelAccumulator()
        if self.laptop_inference:
            self._motion_thread = threading.Thread(
                target=self._motion_loop,
                name="motion-30hz",
                daemon=True,
            )
            self._motion_thread.start()

    def arm(self) -> None:
        with self.lock:
            self.armed = True
            logging.info("ARMED — glasses clutch + packets will move the mouse")
            sys.stdout.flush()

    def disarm(self, reason: str) -> None:
        release_left = False
        with self.lock:
            self.armed = False
            release_left = self._os_left_down
            self.last_buttons = 0
            self._os_left_down = False
            self._left_release_streak = 0
        if release_left:
            try:
                self._mouse.release_left()
                logging.info("left button RELEASE")
            except Exception:
                logging.exception("left button release failed")
        if self._pointer_mapper is not None:
            self._pointer_mapper.set_button_held(False)
        logging.info("DISARMED (%s)", reason)
        sys.stdout.flush()

    def _plan_packet_actions(
        self, decoded: Decoded,
    ) -> tuple[Optional[tuple[int, int]], Optional[str], Optional[int]]:
        """Compute mouse actions under lock; apply pynput outside the lock."""
        move_xy: Optional[tuple[int, int]] = None
        button_action: Optional[str] = None
        wheel_delta: Optional[int] = None

        with self.lock:
            if not self.armed:
                return None, None, None
            if not decoded.output_enabled:
                if self._os_left_down:
                    button_action = "release"
                self.last_buttons = 0
                self._os_left_down = False
                self._left_release_streak = 0
                return None, button_action, None

            use_packet_motion = not self.laptop_inference
            if use_packet_motion and (decoded.dx or decoded.dy):
                move_x, move_y = self._scaled_move(decoded.dx, decoded.dy)
                if move_x or move_y:
                    move_xy = (move_x, move_y)

            if decoded.buttons & BUTTON_WHEEL_UP:
                wheel_delta = 1
            elif decoded.buttons & BUTTON_WHEEL_DOWN:
                wheel_delta = -1

            now_left = bool(decoded.buttons & BUTTON_LEFT)
            if now_left:
                self._left_release_streak = 0
                if not self._os_left_down:
                    button_action = "press"
                    self._os_left_down = True
                self.last_buttons = BUTTON_LEFT
            elif self._os_left_down:
                self._left_release_streak += 1
                if self._left_release_streak >= LEFT_RELEASE_DEBOUNCE:
                    button_action = "release"
                    self._os_left_down = False
                    self._left_release_streak = 0
                self.last_buttons = 0
            else:
                self._left_release_streak = 0
                self.last_buttons = 0

        if button_action == "press" and self._pointer_mapper is not None:
            self._pointer_mapper.set_button_held(True)
        elif button_action == "release" and self._pointer_mapper is not None:
            self._pointer_mapper.set_button_held(False)

        return move_xy, button_action, wheel_delta

    def _apply_mouse_actions(
        self,
        move_xy: Optional[tuple[int, int]],
        button_action: Optional[str],
        wheel_delta: Optional[int],
        sequence: int,
    ) -> None:
        if button_action == "press":
            try:
                self._mouse.press_left()
                logging.info("left button PRESS seq=%s", sequence)
            except Exception:
                logging.exception("left button press failed seq=%s", sequence)
        elif button_action == "release":
            try:
                self._mouse.release_left()
                logging.info("left button RELEASE seq=%s", sequence)
            except Exception:
                logging.exception("left button release failed seq=%s", sequence)

        if move_xy is not None:
            try:
                with self.lock:
                    drawing = self._os_left_down
                self._mouse.move(move_xy[0], move_xy[1], smooth=drawing)
            except Exception:
                logging.exception("mouse move failed seq=%s", sequence)

        if wheel_delta:
            try:
                self._mouse.scroll(wheel_delta * 3)
            except Exception:
                logging.exception("mouse scroll failed seq=%s", sequence)

    def set_gain_ui_callback(self, callback) -> None:
        self._gain_ui_callback = callback

    def apply_remote_gain(self, gain: float, source: str) -> None:
        current = self.sensitivity.get()
        if abs(current - gain) < 0.001:
            return
        self.sensitivity.set(gain)
        logging.info("Gain synced from %s → %.2f", source, gain)
        if self._gain_ui_callback is not None:
            try:
                self._gain_ui_callback(gain)
            except Exception:
                logging.exception("gain UI callback failed")

    def send_gain_to_glasses(self, gain: float, sock: socket.socket) -> None:
        if self.peer_endpoint is None:
            return
        try:
            payload = encode_gain_packet(gain, self.token)
            sock.sendto(payload, self.peer_endpoint)
        except OSError as error:
            logging.warning("Gain sync to glasses failed: %s", error)

    def handle_gain_sync(self, data: bytes, addr) -> None:
        gain = decode_gain_packet(data, self.token)
        if gain is None:
            self.reject_count += 1
            return
        self.apply_remote_gain(gain, source=f"glasses {addr[0]}:{addr[1]}")

    def handle_packet(self, data: bytes, addr, sock: Optional[socket.socket] = None) -> None:
        try:
            self._handle_packet_inner(data, addr)
        except Exception:
            logging.exception("handle_packet failed from %s", addr)

    def _handle_packet_inner(self, data: bytes, addr) -> None:
        decoded = decode_packet(data, self.token)
        if decoded is None:
            self.reject_count += 1
            return
        if not decoded.auth_valid:
            self.reject_count += 1
            logging.warning("reject bad HMAC from %s", addr)
            return

        accepted, reject_reason = self.guard.accept(
            decoded.session_id,
            decoded.sequence,
            decoded.t_ms,
        )
        if not accepted:
            self.reject_count += 1
            self._log_reject(reject_reason or "guard", decoded, addr)
            return

        now = time.monotonic()
        peer = f"{addr[0]}:{addr[1]}"
        self.peer_endpoint = addr
        if peer != self.peer_addr or decoded.session_id != self.active_session_id:
            with self.lock:
                armed = self.armed
            logging.info(
                "glasses linked peer=%s session=%s seq=%s armed=%s",
                peer,
                decoded.session_id,
                decoded.sequence,
                armed,
            )
            self.peer_addr = peer
            self.active_session_id = decoded.session_id

        self.last_decoded = decoded
        center_cursor = False
        with self.lock:
            self.last_accept_mono = now
            self.accept_count += 1
            self.control_output_enabled = decoded.output_enabled
            self.control_motion_paused = decoded.motion_paused
            center_cursor = decoded.center_cursor
            self.control_precision_mode = decoded.precision_mode
            if self._pointer_mapper is not None:
                self._pointer_mapper.set_output_enabled(decoded.output_enabled)
                self._pointer_mapper.set_manual_precision(decoded.precision_mode)
                if decoded.recenter or decoded.center_cursor:
                    self._pointer_mapper.recenter_anchor()
                    self._subpixel.reset()
                elif not decoded.output_enabled:
                    self._subpixel.reset()

        move_xy, button_action, wheel_delta = self._plan_packet_actions(decoded)
        self._apply_mouse_actions(move_xy, button_action, wheel_delta, decoded.sequence)
        if center_cursor:
            self._center_cursor()
        self._maybe_log_status()

    def handle_frame_datagram(self, data: bytes, addr) -> None:
        if not self.laptop_inference or self._frame_reassembler is None:
            return
        chunk = decode_chunk(data, self.token)
        if chunk is None:
            self.frame_reject_count += 1
            return
        if not chunk.auth_valid:
            self.frame_reject_count += 1
            logging.warning("reject bad frame HMAC from %s", addr)
            return
        frame = self._frame_reassembler.ingest(chunk)
        if frame is None:
            return
        if frame.frame_seq == self._last_inferred_frame_seq:
            return
        if self._frame_session_id != frame.session_id:
            self._frame_session_id = frame.session_id
            self._last_inferred_frame_seq = None
            self._mp_video_ts_ms = 0
        self.frame_accept_count += 1
        self._last_inferred_frame_seq = frame.frame_seq
        self._feedback_peer = (addr[0], self.feedback_port)
        self._mp_video_ts_ms += self._frame_inference_period_ms
        if self._hand_tracker is None or self._pointer_mapper is None:
            return
        try:
            track = self._hand_tracker.detect_jpeg(
                frame.jpeg,
                timestamp_ms=self._mp_video_ts_ms,
            )
            self.last_inference_ms = track.inference_ms
            with self.lock:
                motion_paused = self.control_motion_paused
                output_enabled = self.control_output_enabled
                button_held = self._os_left_down
            self._pointer_mapper.set_output_enabled(output_enabled)
            self._pointer_mapper.set_button_held(button_held)
            sample = self._pointer_mapper.update(
                track.landmarks,
                hand_present=track.hand_present,
                motion_paused=motion_paused,
            )
            with self.lock:
                self._last_hand_ok = sample.hand_ok
                armed = self.armed
                paused = self.control_motion_paused
                enabled = self.control_output_enabled
            if (
                armed
                and enabled
                and not paused
                and sample.hand_ok
                and (sample.dx != 0.0 or sample.dy != 0.0)
            ):
                move_x, move_y = self._scaled_move(sample.dx, sample.dy)
                if move_x or move_y:
                    try:
                        with self.lock:
                            drawing = self._os_left_down
                        self._mouse.move(move_x, move_y, smooth=drawing)
                    except Exception:
                        logging.exception("inference motion move failed")
            with self.lock:
                if self._os_left_down:
                    self.last_accept_mono = time.monotonic()
            self._send_landmark_feedback(
                session_id=frame.session_id,
                t_ms=frame.t_ms,
                landmarks=track.landmarks,
                hand_present=track.hand_present,
                precision_active=sample.precision_active or self.control_precision_mode,
            )
        except Exception:
            logging.exception("frame inference failed")

    def _motion_loop(self) -> None:
        while not self._stop.wait(self.motion_period_s):
            if self.laptop_inference or self._pointer_mapper is None:
                continue
            with self.lock:
                if not self.armed or not self.control_output_enabled:
                    continue
                if self.control_motion_paused or not self._last_hand_ok:
                    continue
                dx = self._last_motion_dx
                dy = self._last_motion_dy
                self._last_motion_dx = 0.0
                self._last_motion_dy = 0.0
            if dx == 0.0 and dy == 0.0:
                continue
            move_x, move_y = self._scaled_move(dx, dy)
            if move_x or move_y:
                try:
                    with self.lock:
                        drawing = self._os_left_down
                    self._mouse.move(move_x, move_y, smooth=drawing)
                except Exception:
                    logging.exception("motion loop move failed")

    def watchdog_loop(self) -> None:
        while not self._stop.wait(0.05):
            release_left = False
            with self.lock:
                if not self.armed:
                    continue
                if self.last_accept_mono <= 0:
                    continue
                if time.monotonic() - self.last_accept_mono > self.timeout_s:
                    if self._os_left_down:
                        release_left = True
                        self._os_left_down = False
                        self.last_buttons = 0
                        self._left_release_streak = 0
                        self.timeout_releases += 1
            if release_left:
                try:
                    self._mouse.release_left()
                    logging.warning("RX timeout — buttons released")
                except Exception:
                    logging.exception("timeout button release failed")
                if self._pointer_mapper is not None:
                    self._pointer_mapper.set_button_held(False)

    def _send_landmark_feedback(
        self,
        *,
        session_id: int,
        t_ms: int,
        landmarks,
        hand_present: bool,
        precision_active: bool,
    ) -> None:
        if self._feedback_peer is None:
            return
        if self._feedback_sock is None:
            self._feedback_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self._feedback_sequence = (self._feedback_sequence + 1) & 0xFFFFFFFF
        try:
            payload = encode_landmark_feedback(
                token=self.token,
                session_id=session_id,
                sequence=self._feedback_sequence,
                t_ms=t_ms,
                landmarks=landmarks,
                hand_present=hand_present,
                precision_active=precision_active,
            )
            self._feedback_sock.sendto(payload.packet, self._feedback_peer)
        except Exception:
            logging.exception("landmark feedback send failed")

    def _scaled_move(self, dx: float, dy: float) -> tuple[int, int]:
        with self.lock:
            drawing = self._os_left_down
        return self._subpixel.add(
            dx,
            dy,
            gain=self.sensitivity.get(),
            screen_scale=self.screen_scale,
            screen_width=self.screen_width,
            screen_height=self.screen_height,
            round_output=drawing,
        )

    def _center_cursor(self) -> None:
        if not self.armed:
            return
        self._subpixel.reset()
        try:
            cx = max(0, self.screen_width // 2)
            cy = max(0, self.screen_height // 2)
            self._mouse.set_position(cx, cy)
            logging.info("cursor centered at (%s, %s)", cx, cy)
        except Exception:
            logging.exception("center cursor failed")

    def _log_reject(self, reason: str, decoded: Decoded, addr) -> None:
        now = time.monotonic()
        if now - self._last_reject_log_mono < 1.0:
            return
        self._last_reject_log_mono = now
        logging.warning(
            "reject %s from %s session=%s seq=%s t_ms=%s",
            reason,
            addr,
            decoded.session_id,
            decoded.sequence,
            decoded.t_ms,
        )

    def _maybe_log_status(self) -> None:
        now = time.monotonic()
        if now - self._last_status_log_mono < self.status_interval_s:
            return
        if self.last_accept_mono <= 0:
            return
        self._last_status_log_mono = now
        age_s = now - self.last_accept_mono
        decoded = self.last_decoded
        with self.lock:
            armed = self.armed
        if decoded is None:
            return
        logging.info(
            "rx peer=%s session=%s seq=%s armed=%s output=%s hand=%s dx=%s dy=%s "
            "gain=%.2f accept=%s reject=%s last=%.2fs ago",
            self.peer_addr,
            decoded.session_id,
            decoded.sequence,
            armed,
            decoded.output_enabled,
            decoded.hand_ok,
            decoded.dx,
            decoded.dy,
            self.sensitivity.get(),
            self.accept_count,
            self.reject_count,
            age_s,
        )

    def stop(self) -> None:
        self._stop.set()
        if self._hand_tracker is not None:
            self._hand_tracker.close()
        self.disarm("shutdown")


def run_frame_udp_server(
    agent: MouseAgent,
    host: str,
    port: int,
    stop_event: threading.Event,
) -> None:
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        sock.bind((host, port))
    except OSError as error:
        logging.error("Frame UDP bind failed on %s:%s — %s", host, port, error)
        stop_event.set()
        return
    sock.settimeout(0.5)
    logging.info("Listening frame UDP %s:%s (RKFR)", host, port)
    try:
        while not stop_event.is_set():
            try:
                data, addr = sock.recvfrom(65535)
            except socket.timeout:
                continue
            agent.handle_frame_datagram(data, addr)
    finally:
        sock.close()


def probe_udp_port(host: str, port: int) -> Optional[str]:
    """Return an error message if UDP port cannot be bound."""
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        sock.bind((host, port))
    except OSError as error:
        return (
            f"UDP port {port} is not available ({error}).\n\n"
            "Close any other mouse_agent window, then try again."
        )
    finally:
        sock.close()
    return None


def show_error_dialog(title: str, message: str) -> None:
    try:
        import tkinter as tk
        from tkinter import messagebox

        root = tk.Tk()
        root.withdraw()
        root.attributes("-topmost", True)
        messagebox.showerror(title, message, parent=root)
        root.destroy()
    except Exception:
        logging.error("%s: %s", title, message)


def start_console_arm_listener(agent: MouseAgent, stop_event: threading.Event) -> None:
    """Allow arming from the cmd window when hotkeys/GUI fail (type a + Enter)."""

    def loop() -> None:
        while not stop_event.is_set():
            try:
                line = sys.stdin.readline()
            except (EOFError, OSError):
                break
            if stop_event.is_set():
                break
            cmd = line.strip().lower()
            if cmd in ("a", "arm"):
                agent.arm()
            elif cmd in ("d", "disarm"):
                agent.disarm("console")

    thread = threading.Thread(target=loop, name="console-arm", daemon=True)
    thread.start()


def _center_toplevel(root: "tk.Misc", width: int, height: int) -> None:
    root.update_idletasks()
    sw = root.winfo_screenwidth()
    sh = root.winfo_screenheight()
    x = max(0, (sw - width) // 2)
    y = max(0, (sh - height) // 3)
    root.geometry(f"{width}x{height}+{x}+{y}")


def run_udp_server(
    agent: MouseAgent,
    host: str,
    port: int,
    stop_event: threading.Event,
    udp_sock_holder: Optional[dict] = None,
) -> None:
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    if udp_sock_holder is not None:
        udp_sock_holder["sock"] = sock
    try:
        sock.bind((host, port))
    except OSError as error:
        logging.error("UDP bind failed on %s:%s — %s", host, port, error)
        stop_event.set()
        return
    sock.settimeout(0.5)
    logging.info(
        "Listening UDP %s:%s token=%r — F8/F9 arm, F10/Esc disarm. Starts DISARMED.",
        host,
        port,
        agent.token,
    )
    logging.info(
        "Use the slider window Arm button if hotkeys do not work in cmd.exe.",
    )
    logging.info(
        "Screen %sx%s  glasses ref %sx%s  scale=%s  gain=%.2f (synced from glasses)",
        agent.screen_width,
        agent.screen_height,
        GLASSES_REF_WIDTH,
        GLASSES_REF_HEIGHT,
        agent.screen_scale,
        agent.sensitivity.get(),
    )
    try:
        while not stop_event.is_set():
            try:
                data, addr = sock.recvfrom(2048)
            except socket.timeout:
                continue
            if len(data) >= 4 and struct.unpack("<I", data[:4])[0] == CONFIG_MAGIC:
                agent.handle_gain_sync(data, addr)
                continue
            agent.handle_packet(data, addr, sock)
    finally:
        agent.stop()
        sock.close()


def run_sensitivity_gui(
    agent: MouseAgent,
    sensitivity: SensitivitySettings,
    stop_event: threading.Event,
    udp_sock_holder: dict,
) -> None:
    import tkinter as tk

    def _shutdown() -> None:
        sensitivity.save()
        stop_event.set()

    root = tk.Tk()
    root.title("Rokid Mouse Agent")
    root.minsize(400, 280)
    root.configure(bg="#f0f0f0")
    root.protocol("WM_DELETE_WINDOW", lambda: (_shutdown(), root.destroy()))

    header = tk.Label(
        root,
        text="Rokid Mouse Agent",
        font=("Segoe UI", 12, "bold"),
        bg="#f0f0f0",
    )
    header.pack(pady=(12, 4))

    tk.Label(
        root,
        text="Sensitivity — synced with glasses swipe (same value on both sides)",
        font=("Segoe UI", 9),
        bg="#f0f0f0",
    ).pack(pady=(0, 8))

    slider_row = tk.Frame(root, bg="#f0f0f0")
    slider_row.pack(fill=tk.X, padx=16, pady=4)

    gain_var = tk.DoubleVar(value=sensitivity.get())
    value_label = tk.Label(slider_row, text=f"{sensitivity.get():.2f}", width=6, bg="#f0f0f0")

    def on_gain_change(_value: str) -> None:
        gain = float(gain_var.get())
        sensitivity.set(gain)
        value_label.config(text=f"{gain:.2f}")
        sock = udp_sock_holder.get("sock")
        if sock is not None:
            agent.send_gain_to_glasses(gain, sock)

    agent.set_gain_ui_callback(lambda gain: root.after(0, lambda: (
        gain_var.set(gain),
        value_label.config(text=f"{gain:.2f}"),
    )))

    slider = tk.Scale(
        slider_row,
        from_=GAIN_MIN,
        to=GAIN_MAX,
        orient=tk.HORIZONTAL,
        length=300,
        resolution=0.01,
        variable=gain_var,
        command=on_gain_change,
        bg="#f0f0f0",
        highlightthickness=0,
    )
    slider.pack(side=tk.LEFT, fill=tk.X, expand=True)
    value_label.pack(side=tk.LEFT, padx=(8, 0))

    status_armed = tk.Label(root, text="Status: DISARMED", font=("Segoe UI", 10), bg="#f0f0f0")
    status_armed.pack(anchor="w", padx=16, pady=(12, 2))
    status_peer = tk.Label(root, text="Glasses: (waiting)", font=("Segoe UI", 9), bg="#f0f0f0")
    status_peer.pack(anchor="w", padx=16)
    status_rx = tk.Label(root, text="Packets: accept=0 reject=0", font=("Segoe UI", 9), bg="#f0f0f0")
    status_rx.pack(anchor="w", padx=16, pady=(0, 10))

    button_row = tk.Frame(root, bg="#f0f0f0")
    button_row.pack(pady=8)

    def do_arm(_event=None) -> None:
        agent.arm()
        refresh_status()

    def do_disarm(_event=None) -> None:
        agent.disarm("GUI")
        refresh_status()

    tk.Button(
        button_row,
        text="ARM",
        command=do_arm,
        width=14,
        height=2,
        bg="#2E7D32",
        fg="white",
        activebackground="#1B5E20",
        activeforeground="white",
        font=("Segoe UI", 11, "bold"),
    ).pack(side=tk.LEFT, padx=(0, 10))
    tk.Button(
        button_row,
        text="Disarm",
        command=do_disarm,
        width=14,
        height=2,
        font=("Segoe UI", 10),
    ).pack(side=tk.LEFT)

    tk.Label(
        root,
        text="Or type  a  + Enter in the black cmd window below",
        font=("Segoe UI", 9),
        bg="#f0f0f0",
        fg="#333333",
    ).pack(pady=(4, 12))

    root.bind("<F8>", do_arm)
    root.bind("<F9>", do_arm)
    root.bind("<F10>", do_disarm)
    root.bind("<Escape>", do_disarm)

    def refresh_status() -> None:
        if agent.lock.acquire(blocking=False):
            try:
                armed = agent.armed
                accept = agent.accept_count
                reject = agent.reject_count
                peer = agent.peer_addr
            finally:
                agent.lock.release()
        else:
            armed = agent.armed
            accept = agent.accept_count
            reject = agent.reject_count
            peer = agent.peer_addr
        status_armed.config(
            text=f"Status: {'ARMED' if armed else 'DISARMED'}",
            fg="#1B5E20" if armed else "#B71C1C",
        )
        status_peer.config(text=f"Glasses: {peer or '(waiting)'}")
        status_rx.config(
            text=f"Packets: accept={accept} reject={reject}  gain={sensitivity.get():.2f}",
        )
        if not stop_event.is_set():
            root.after(400, refresh_status)

    _center_toplevel(root, 420, 300)
    root.update_idletasks()
    root.deiconify()
    root.lift()
    root.attributes("-topmost", True)
    root.after(500, lambda: root.attributes("-topmost", False))
    root.focus_force()

    refresh_status()
    logging.info("Sensitivity slider window open — click ARM or type a + Enter in cmd")
    sys.stdout.flush()
    root.mainloop()
    _shutdown()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Rokid MouseLink UDP agent (Stage 4)")
    parser.add_argument("--host", default="0.0.0.0", help="Bind address")
    parser.add_argument("--port", type=int, default=9460)
    parser.add_argument("--token", default="dev-token")
    parser.add_argument("--timeout-ms", type=int, default=200)
    parser.add_argument(
        "--status-interval",
        type=float,
        default=2.0,
        help="Seconds between live rx status lines in console (0 = off)",
    )
    parser.add_argument(
        "--gain",
        type=float,
        default=None,
        help=f"Sensitivity gain ({GAIN_MIN}–{GAIN_MAX}); default from saved settings or {GAIN_DEFAULT}",
    )
    parser.add_argument(
        "--no-screen-scale",
        action="store_true",
        help="Do not scale by laptop screen / 480×640 reference",
    )
    parser.add_argument(
        "--gui",
        action="store_true",
        help="Show sensitivity slider window (recommended)",
    )
    parser.add_argument(
        "--no-gui",
        action="store_true",
        help="Console-only mode (no slider window)",
    )
    parser.add_argument(
        "--auto-arm",
        action="store_true",
        help="Arm immediately on start (skips manual ARM step)",
    )
    parser.add_argument(
        "--laptop-inference",
        action="store_true",
        help="Enable laptop-side MediaPipe from RKFR frame port (default on)",
    )
    parser.add_argument(
        "--no-laptop-inference",
        action="store_true",
        help="Legacy mode: motion dx/dy only from control packets",
    )
    parser.add_argument(
        "--frame-port",
        type=int,
        default=9461,
        help="UDP port for RKFR JPEG frames from glasses",
    )
    parser.add_argument(
        "--motion-hz",
        type=float,
        default=30.0,
        help="Fixed-rate mouse injection when --laptop-inference",
    )
    parser.add_argument(
        "--model",
        type=Path,
        default=None,
        help="Path to hand_landmarker.task (default: models/hand_landmarker.task)",
    )
    parser.add_argument(
        "--feedback-port",
        type=int,
        default=9462,
        help="UDP port for RKLM landmark feedback to glasses",
    )
    args = parser.parse_args(argv)

    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(message)s",
    )

    settings_path = Path(__file__).resolve().parent / SETTINGS_FILENAME
    sensitivity = SensitivitySettings.load(settings_path, default_gain=GAIN_DEFAULT)
    if args.gain is not None:
        sensitivity.set(args.gain)

    screen_w, screen_h = primary_screen_size()
    model_path = args.model or (Path(__file__).resolve().parent / "models" / "hand_landmarker.task")
    use_laptop = not args.no_laptop_inference
    if args.laptop_inference:
        use_laptop = True
    agent = MouseAgent(
        token=args.token,
        sensitivity=sensitivity,
        timeout_ms=args.timeout_ms,
        status_interval_s=args.status_interval,
        screen_scale=not args.no_screen_scale,
        screen_width=screen_w,
        screen_height=screen_h,
        laptop_inference=use_laptop,
        model_path=model_path,
        motion_hz=args.motion_hz,
        feedback_port=args.feedback_port,
    )

    bind_error = probe_udp_port(args.host, args.port)
    if bind_error:
        logging.error(bind_error.replace("\n", " "))
        show_error_dialog("Rokid Mouse Agent", bind_error)
        return 1
    if agent.laptop_inference:
        frame_bind_error = probe_udp_port(args.host, args.frame_port)
        if frame_bind_error:
            logging.error(frame_bind_error.replace("\n", " "))
            show_error_dialog("Rokid Mouse Agent", frame_bind_error)
            return 1

    stop_event = threading.Event()
    watchdog = threading.Thread(target=agent.watchdog_loop, name="rx-watchdog", daemon=True)
    watchdog.start()

    listener: Optional[keyboard.Listener] = None

    def on_press(key) -> None:
        try:
            if key in (keyboard.Key.f8, keyboard.Key.f9):
                agent.arm()
            elif key in (keyboard.Key.f10, keyboard.Key.esc):
                agent.disarm(str(key))
        except Exception:
            logging.exception("key handler")

    try:
        listener = keyboard.Listener(on_press=on_press)
        listener.start()
    except Exception:
        logging.exception("pynput keyboard listener failed — use ARM button or type a + Enter")

    start_console_arm_listener(agent, stop_event)
    print("", flush=True)
    print("=" * 52, flush=True)
    print("  TYPE  a  AND PRESS ENTER  TO ARM THE MOUSE", flush=True)
    print("  TYPE  d  AND PRESS ENTER  TO DISARM", flush=True)
    print("=" * 52, flush=True)
    print("", flush=True)

    use_gui = args.gui or not args.no_gui
    udp_sock_holder: dict = {}

    udp_thread = threading.Thread(
        target=run_udp_server,
        args=(agent, args.host, args.port, stop_event, udp_sock_holder),
        name="udp-server",
        daemon=True,
    )
    udp_thread.start()

    frame_thread: Optional[threading.Thread] = None
    if agent.laptop_inference:
        frame_thread = threading.Thread(
            target=run_frame_udp_server,
            args=(agent, args.host, args.frame_port, stop_event),
            name="frame-udp-server",
            daemon=True,
        )
        frame_thread.start()

    if args.auto_arm:
        agent.arm()

    try:
        if use_gui:
            try:
                run_sensitivity_gui(agent, sensitivity, stop_event, udp_sock_holder)
            except Exception:
                logging.exception("GUI failed to start")
                show_error_dialog(
                    "Rokid Mouse Agent",
                    "Could not open the sensitivity window.\n\n"
                    "Try: python -m tkinter\n"
                    "Or run: python mouse_agent.py --no-gui\n\n"
                    "See the console for details.",
                )
                return 1
        else:
            logging.info("Console mode — Ctrl+C to quit")
            while not stop_event.is_set():
                time.sleep(0.2)
    except KeyboardInterrupt:
        logging.info("Interrupted")
    finally:
        stop_event.set()
        sensitivity.save()
        if listener is not None:
            listener.stop()
        udp_thread.join(timeout=2.0)
        if frame_thread is not None:
            frame_thread.join(timeout=2.0)
    return 0


if __name__ == "__main__":
    sys.exit(main())
