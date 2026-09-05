"""Touch-gated trackpad pointer mapping (ported from Kotlin PointerMapper)."""

from __future__ import annotations

import math
from dataclasses import dataclass
from typing import Optional, Sequence

from one_euro_filter import OneEuroFilter2D


WRIST = 0
INDEX_TIP = 8
MIDDLE_MCP = 9
PALM_INDICES = (0, 5, 9, 13, 17)

DEFAULT_MOVE_DEADZONE = 0.008
DEFAULT_MAX_DELTA_PER_FRAME = 160.0
DEFAULT_FRAME_DT_S = 1.0 / 30.0

# 1€ filter — low lag on fast motion, stable at rest (~30 Hz).
ONE_EURO_MIN_CUTOFF = 2.0
ONE_EURO_BETA = 0.025
ONE_EURO_D_CUTOFF = 1.0

# Idle jitter: lock anchor when raw wrist-relative motion stays tiny.
STATIONARY_RAW_DELTA = 0.0010
STATIONARY_LOCK_FRAMES = 5
STATIONARY_UNLOCK_RAW = 0.0035

# Slow-move precision for small UI targets.
SLOW_SMOOTH_DELTA = 0.0028
PRECISION_GAIN_MULT = 0.30
PRECISION_DEADZONE = 0.016
MANUAL_PRECISION_GAIN_MULT = 0.22
MANUAL_PRECISION_DEADZONE = 0.020

# While left button is held (Paint drag), use softer gating for smooth curves.
DRAWING_DEADZONE = 0.0
DRAWING_GAIN_MULT = 1.0
DRAWING_EMA_ALPHA = 0.82
DRAWING_MAX_DELTA_PER_FRAME = 16.0
DRAWING_ACCEL_EXPONENT = 1.0
DRAWING_COAST_FRAMES = 12
DRAWING_JUMP_NORM = 0.008

MIN_HAND_SCALE = 1e-3


@dataclass
class Landmark:
    x: float
    y: float
    z: float = 0.0


@dataclass
class PointerSample:
    dx: float
    dy: float
    hand_ok: bool
    gesture: str = "TRACKING"
    precision_active: bool = False
    stationary_locked: bool = False


def _transform_point(x: float, y: float, rotation_cw_deg: int) -> tuple[float, float]:
    """Apply extra 270° CW display rotation (matches HandDisplayTransform.kt)."""
    rotation = rotation_cw_deg % 360
    if rotation == 0:
        return x, y
    if rotation == 90:
        return 1.0 - y, x
    if rotation == 180:
        return 1.0 - x, 1.0 - y
    if rotation == 270:
        return y, 1.0 - x
    return x, y


def to_display_landmarks(
    landmarks: Sequence[tuple[float, float, float]],
    extra_rotation_cw: int = 270,
) -> list[Landmark]:
    out: list[Landmark] = []
    for x, y, z in landmarks:
        dx, dy = _transform_point(x, y, extra_rotation_cw)
        out.append(Landmark(dx, dy, z))
    return out


def hand_scale(landmarks: Sequence[Landmark]) -> float:
    """Wrist → middle MCP span (matches HandPoseMath.handScale on glasses)."""
    wrist = landmarks[WRIST]
    middle = landmarks[MIDDLE_MCP]
    scale = math.hypot(middle.x - wrist.x, middle.y - wrist.y)
    return max(scale, MIN_HAND_SCALE)


def palm_center(landmarks: Sequence[Landmark]) -> tuple[float, float]:
    sx = sy = 0.0
    for i in PALM_INDICES:
        sx += landmarks[i].x
        sy += landmarks[i].y
    n = float(len(PALM_INDICES))
    return sx / n, sy / n


def index_tip(landmarks: Sequence[Landmark]) -> tuple[float, float]:
    tip = landmarks[INDEX_TIP]
    return tip.x, tip.y


def wrist_relative_point(
    landmarks: Sequence[Landmark],
    *,
    use_palm_center: bool,
) -> tuple[float, float]:
    """Normalize pointer position by hand size — reduces distance/posture sensitivity."""
    wrist = landmarks[WRIST]
    scale = hand_scale(landmarks)
    if use_palm_center:
        px, py = palm_center(landmarks)
    else:
        px, py = index_tip(landmarks)
    return (px - wrist.x) / scale, (py - wrist.y) / scale


class TouchGatedPointerMapper:
    def __init__(
        self,
        *,
        use_palm_center: bool = False,
        move_deadzone: float = DEFAULT_MOVE_DEADZONE,
        sensitivity: float = 2000.0,
        acceleration_exponent: float = 1.1,
        max_delta_per_frame: float = DEFAULT_MAX_DELTA_PER_FRAME,
        wrist_relative: bool = True,
        use_one_euro: bool = True,
        one_euro_min_cutoff: float = ONE_EURO_MIN_CUTOFF,
        one_euro_beta: float = ONE_EURO_BETA,
        frame_dt_s: float = DEFAULT_FRAME_DT_S,
        index_tip_smooth_alpha: float = 0.3,
        adaptive_smoothing: bool = False,
    ) -> None:
        self.use_palm_center = use_palm_center
        self.move_deadzone = move_deadzone
        self.sensitivity = sensitivity
        self.acceleration_exponent = acceleration_exponent
        self.max_delta_per_frame = max_delta_per_frame
        self.wrist_relative = wrist_relative
        self.use_one_euro = use_one_euro
        self.frame_dt_s = frame_dt_s
        self.index_tip_smooth_alpha = index_tip_smooth_alpha
        self.adaptive_smoothing = adaptive_smoothing
        self.output_enabled = False
        self.manual_precision = False
        self._button_held = False
        self._has_anchor = False
        self._hand_was_present = False
        self._stationary_locked = False
        self._stationary_frames = 0
        self._prev_unfiltered_x: Optional[float] = None
        self._prev_unfiltered_y: Optional[float] = None
        self._last_raw_x: Optional[float] = None
        self._last_raw_y: Optional[float] = None
        self._smooth_x: Optional[float] = None
        self._smooth_y: Optional[float] = None
        self._prev_unsmoothed_x: Optional[float] = None
        self._prev_unsmoothed_y: Optional[float] = None
        self._filter_time_s = 0.0
        self._one_euro = OneEuroFilter2D(
            min_cutoff=one_euro_min_cutoff,
            beta=one_euro_beta,
            d_cutoff=ONE_EURO_D_CUTOFF,
        )
        self._last_good_landmarks: Optional[list[Landmark]] = None
        self._coast_remaining = 0

    def set_output_enabled(self, enabled: bool) -> None:
        if self.output_enabled == enabled:
            return
        self.output_enabled = enabled
        self.recenter_anchor()
        if not enabled:
            self._has_anchor = False
            self._hand_was_present = False

    def set_manual_precision(self, enabled: bool) -> None:
        self.manual_precision = enabled

    def set_button_held(self, held: bool) -> None:
        """Paint/drag mode: smoother curves, no idle lock or slow-move precision."""
        if self._button_held == held:
            return
        self._button_held = held
        if held:
            self._stationary_locked = False
            self._stationary_frames = 0
            self._coast_remaining = DRAWING_COAST_FRAMES
        else:
            self._last_good_landmarks = None
            self._coast_remaining = 0

    def recenter_anchor(self) -> None:
        self._has_anchor = False
        self._stationary_locked = False
        self._stationary_frames = 0
        self._prev_unfiltered_x = None
        self._prev_unfiltered_y = None
        self._last_raw_x = None
        self._last_raw_y = None
        self._smooth_x = None
        self._smooth_y = None
        self._prev_unsmoothed_x = None
        self._prev_unsmoothed_y = None
        self._filter_time_s = 0.0
        self._one_euro.reset()
        self._last_good_landmarks = None
        self._coast_remaining = 0

    def _raw_pointer(self, landmarks: Sequence[Landmark]) -> tuple[float, float]:
        if self.wrist_relative:
            return wrist_relative_point(landmarks, use_palm_center=self.use_palm_center)
        if self.use_palm_center:
            return palm_center(landmarks)
        return index_tip(landmarks)

    def _update_stationary_lock(self, raw_x: float, raw_y: float) -> None:
        if self._prev_unfiltered_x is None or self._prev_unfiltered_y is None:
            self._prev_unfiltered_x = raw_x
            self._prev_unfiltered_y = raw_y
            return
        raw_mag = math.hypot(raw_x - self._prev_unfiltered_x, raw_y - self._prev_unfiltered_y)
        self._prev_unfiltered_x = raw_x
        self._prev_unfiltered_y = raw_y
        if self._stationary_locked:
            if raw_mag >= STATIONARY_UNLOCK_RAW:
                self._stationary_locked = False
                self._stationary_frames = 0
            return
        if raw_mag < STATIONARY_RAW_DELTA:
            self._stationary_frames += 1
            if self._stationary_frames >= STATIONARY_LOCK_FRAMES:
                self._stationary_locked = True
        else:
            self._stationary_frames = 0

    def _adaptive_alpha(self, raw_x: float, raw_y: float) -> float:
        base = self.index_tip_smooth_alpha
        if self._prev_unsmoothed_x is None or self._prev_unsmoothed_y is None:
            return base
        mag = math.hypot(raw_x - self._prev_unsmoothed_x, raw_y - self._prev_unsmoothed_y)
        fast_start = 0.004
        fast_full = 0.014
        fast_alpha = 0.85
        if mag <= fast_start:
            return base
        if mag >= fast_full:
            return fast_alpha
        t = (mag - fast_start) / (fast_full - fast_start)
        return base + t * (fast_alpha - base)

    def _smooth_ema(self, raw_x: float, raw_y: float, *, alpha: Optional[float] = None) -> tuple[float, float]:
        if alpha is None:
            use_alpha = (
                self._adaptive_alpha(raw_x, raw_y)
                if self.adaptive_smoothing
                else self.index_tip_smooth_alpha
            )
        else:
            use_alpha = alpha
        self._prev_unsmoothed_x = raw_x
        self._prev_unsmoothed_y = raw_y
        if self._smooth_x is None or self._smooth_y is None:
            self._smooth_x = raw_x
            self._smooth_y = raw_y
        else:
            self._smooth_x = use_alpha * raw_x + (1.0 - use_alpha) * self._smooth_x
            self._smooth_y = use_alpha * raw_y + (1.0 - use_alpha) * self._smooth_y
        return self._smooth_x, self._smooth_y

    def _smooth_pointer(self, raw_x: float, raw_y: float) -> tuple[float, float]:
        if self._button_held:
            # 1€ on X/Y separately bends diagonals into stair-steps; use shared EMA while drawing.
            return self._smooth_ema(raw_x, raw_y, alpha=DRAWING_EMA_ALPHA)
        if self.use_one_euro:
            self._filter_time_s += self.frame_dt_s
            return self._one_euro.filter(self._filter_time_s, raw_x, raw_y)
        return self._smooth_ema(raw_x, raw_y)

    def _precision_profile(
        self,
        smooth_x: float,
        smooth_y: float,
    ) -> tuple[bool, float, float, float]:
        if self._button_held:
            return (
                False,
                DRAWING_GAIN_MULT,
                DRAWING_DEADZONE,
                1.0,
            )
        if self.manual_precision:
            return True, MANUAL_PRECISION_GAIN_MULT, MANUAL_PRECISION_DEADZONE, 1.0
        if self._last_raw_x is None or self._last_raw_y is None:
            return False, 1.0, self.move_deadzone, 1.0
        smooth_mag = math.hypot(smooth_x - self._last_raw_x, smooth_y - self._last_raw_y)
        if smooth_mag < SLOW_SMOOTH_DELTA:
            return True, PRECISION_GAIN_MULT, PRECISION_DEADZONE, 1.0
        return False, 1.0, self.move_deadzone, 1.0

    def _map_delta_vector(
        self,
        dx_norm: float,
        dy_norm: float,
        *,
        deadzone: float,
        gain_mult: float,
        linear: bool = False,
        max_delta: Optional[float] = None,
    ) -> tuple[float, float]:
        """Map a 2D delta with a radial deadzone so diagonals stay smooth (not staircase)."""
        mag = math.hypot(dx_norm, dy_norm)
        if mag <= 0.0 or mag < deadzone:
            return 0.0, 0.0
        usable = mag - deadzone
        exponent = DRAWING_ACCEL_EXPONENT if linear else self.acceleration_exponent
        cap = max_delta if max_delta is not None else self.max_delta_per_frame
        shaped = usable if exponent == 1.0 else usable ** exponent
        scale = shaped * self.sensitivity * gain_mult / mag
        dx = dx_norm * scale
        dy = dy_norm * scale
        out_mag = math.hypot(dx, dy)
        if out_mag > cap:
            clip = cap / out_mag
            dx *= clip
            dy *= clip
        return dx, dy

    def _map_axis(self, delta_norm: float, *, deadzone: float, gain_mult: float) -> float:
        mag = abs(delta_norm)
        if mag < deadzone:
            return 0.0
        shaped = (mag - deadzone) ** self.acceleration_exponent
        scaled = shaped * self.sensitivity * gain_mult * math.copysign(1.0, delta_norm)
        return max(-self.max_delta_per_frame, min(self.max_delta_per_frame, scaled))

    def update(
        self,
        landmarks: Sequence[Landmark],
        *,
        hand_present: bool,
        motion_paused: bool = False,
    ) -> PointerSample:
        coasting = False
        if not hand_present or len(landmarks) < 21:
            if (
                self._button_held
                and self._last_good_landmarks is not None
                and self._coast_remaining > 0
            ):
                landmarks = self._last_good_landmarks
                hand_present = True
                coasting = True
                self._coast_remaining -= 1
            else:
                self._hand_was_present = False
                if self._button_held:
                    return PointerSample(0.0, 0.0, False, "LOST")
                self.recenter_anchor()
                gesture = "LOST" if self.output_enabled else "IDLE"
                return PointerSample(0.0, 0.0, False, gesture)
        elif self._button_held:
            self._last_good_landmarks = list(landmarks)
            self._coast_remaining = DRAWING_COAST_FRAMES

        reacquired = not self._hand_was_present and not coasting
        if reacquired:
            if self._button_held:
                self._has_anchor = False
            else:
                self.recenter_anchor()
        self._hand_was_present = True

        unfiltered_x, unfiltered_y = self._raw_pointer(landmarks)
        if not self._button_held:
            self._update_stationary_lock(unfiltered_x, unfiltered_y)
        else:
            self._stationary_locked = False
            self._stationary_frames = 0

        if reacquired and self._button_held:
            self._smooth_x = unfiltered_x
            self._smooth_y = unfiltered_y
            smooth_x, smooth_y = unfiltered_x, unfiltered_y
        else:
            smooth_x, smooth_y = self._smooth_pointer(unfiltered_x, unfiltered_y)

        precision_active, gain_mult, deadzone, _ = self._precision_profile(smooth_x, smooth_y)
        dx = dy = 0.0
        if self.output_enabled and not motion_paused:
            if self._stationary_locked:
                dx = dy = 0.0
            elif self._has_anchor and self._last_raw_x is not None and self._last_raw_y is not None:
                norm_dx = smooth_x - self._last_raw_x
                norm_dy = smooth_y - self._last_raw_y
                if self._button_held and math.hypot(norm_dx, norm_dy) > DRAWING_JUMP_NORM:
                    dx = dy = 0.0
                    self._last_raw_x = smooth_x
                    self._last_raw_y = smooth_y
                else:
                    dx, dy = self._map_delta_vector(
                        norm_dx,
                        norm_dy,
                        deadzone=deadzone,
                        gain_mult=gain_mult,
                        linear=self._button_held,
                        max_delta=DRAWING_MAX_DELTA_PER_FRAME if self._button_held else None,
                    )
            else:
                self._has_anchor = True
        elif motion_paused:
            self.recenter_anchor()

        self._last_raw_x = smooth_x
        self._last_raw_y = smooth_y

        if not self.output_enabled:
            gesture = "IDLE"
        elif motion_paused:
            gesture = "PAUSED"
        elif self._stationary_locked:
            gesture = "STATIONARY"
        elif precision_active:
            gesture = "PRECISION"
        else:
            gesture = "TRACKING"
        return PointerSample(
            dx,
            dy,
            True,
            gesture,
            precision_active=precision_active,
            stationary_locked=self._stationary_locked,
        )
