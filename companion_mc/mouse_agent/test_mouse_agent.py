#!/usr/bin/env python3
"""Unit tests for mouse_agent ReceiveGuard reconnect behaviour."""

import struct
import unittest
from pathlib import Path
from unittest.mock import MagicMock

from frame_link import (
    AUTH_TAG_SIZE,
    FLAG_OUTPUT_ENABLED,
    HEADER_SIZE,
    MAGIC,
    VERSION,
    FrameReassembler,
    decode_chunk,
    hmac_tag,
)
from landmark_feedback import (
    LANDMARK_COUNT,
    decode_landmark_feedback,
    encode_landmark_feedback,
)
from mouse_agent import (
    BUTTON_LEFT,
    FLAG_OUTPUT_ENABLED,
    GLASSES_REF_HEIGHT,
    GLASSES_REF_WIDTH,
    MAGIC,
    Decoded,
    MouseAgent,
    ReceiveGuard,
    SubpixelAccumulator,
    SensitivitySettings,
    TMS_REWIND_RESET_MS,
    scale_motion,
)
from pointer_mapper import (
    DRAWING_COAST_FRAMES,
    INDEX_TIP,
    MIDDLE_MCP,
    STATIONARY_LOCK_FRAMES,
    WRIST,
    Landmark,
    TouchGatedPointerMapper,
    wrist_relative_point,
)


def make_hand_landmarks(
    *,
    tip_x: float = 0.50,
    tip_y: float = 0.50,
    wrist_x: float = 0.40,
    wrist_y: float = 0.60,
    middle_x: float = 0.42,
    middle_y: float = 0.55,
) -> list[Landmark]:
    """Minimal 21-landmark hand with realistic wrist / middle MCP geometry."""
    landmarks = [Landmark(0.5, 0.5)] * 21
    landmarks[WRIST] = Landmark(wrist_x, wrist_y)
    landmarks[MIDDLE_MCP] = Landmark(middle_x, middle_y)
    landmarks[INDEX_TIP] = Landmark(tip_x, tip_y)
    return landmarks


def encode_test_chunk(
    token: str,
    *,
    session_id: int = 1,
    frame_seq: int = 10,
    chunk_index: int = 0,
    chunk_total: int = 1,
    payload: bytes = b"jpeg",
) -> bytes:
    flags = FLAG_OUTPUT_ENABLED
    body = struct.pack(
        "<IBBHIIHHHHHH",
        MAGIC,
        VERSION,
        flags,
        session_id,
        frame_seq,
        1000,
        270,
        320,
        240,
        chunk_index,
        chunk_total,
        len(payload),
    ) + payload
    return body + hmac_tag(token, body)


class FrameLinkTest(unittest.TestCase):
    def test_decode_and_reassemble(self) -> None:
        token = "dev-token"
        payload = b"x" * 1500
        c0 = encode_test_chunk(token, chunk_index=0, chunk_total=2, payload=payload[:1200])
        c1 = encode_test_chunk(token, chunk_index=1, chunk_total=2, payload=payload[1200:])
        assembler = FrameReassembler()
        d0 = decode_chunk(c0, token)
        d1 = decode_chunk(c1, token)
        assert d0 is not None and d1 is not None
        self.assertIsNone(assembler.ingest(d0))
        frame = assembler.ingest(d1)
        self.assertIsNotNone(frame)
        assert frame is not None
        self.assertEqual(frame.jpeg, payload)


class LandmarkFeedbackTest(unittest.TestCase):
    def test_round_trip(self) -> None:
        token = "dev-token"
        landmarks = make_hand_landmarks(tip_x=0.55, tip_y=0.45)
        encoded = encode_landmark_feedback(
            token=token,
            session_id=7,
            sequence=99,
            t_ms=1234,
            landmarks=landmarks,
            hand_present=True,
            precision_active=True,
        )
        decoded = decode_landmark_feedback(encoded.packet, token)
        self.assertIsNotNone(decoded)
        assert decoded is not None
        self.assertTrue(decoded.auth_valid)
        self.assertTrue(decoded.hand_present)
        self.assertTrue(decoded.precision_active)
        self.assertEqual(decoded.session_id, 7)
        self.assertAlmostEqual(decoded.landmarks[INDEX_TIP][0], 0.55, places=4)
        self.assertAlmostEqual(decoded.landmarks[INDEX_TIP][1], 0.45, places=4)
        self.assertEqual(len(decoded.landmarks), LANDMARK_COUNT)


class PointerMapperTest(unittest.TestCase):
    def test_deadzone_zeros_small_motion(self) -> None:
        mapper = TouchGatedPointerMapper(
            move_deadzone=0.01,
            sensitivity=2000.0,
            use_one_euro=False,
            index_tip_smooth_alpha=1.0,
        )
        mapper.set_output_enabled(True)
        base = make_hand_landmarks()
        mapper.update(base, hand_present=True)
        sample = mapper.update(base, hand_present=True)
        self.assertEqual(sample.dx, 0.0)
        self.assertEqual(sample.dy, 0.0)

    def test_recenter_clears_anchor(self) -> None:
        mapper = TouchGatedPointerMapper(
            move_deadzone=0.001,
            sensitivity=2000.0,
            use_one_euro=False,
            index_tip_smooth_alpha=1.0,
        )
        mapper.set_output_enabled(True)
        base = make_hand_landmarks()
        mapper.update(base, hand_present=True)
        mapper.recenter_anchor()
        sample = mapper.update(
            make_hand_landmarks(tip_x=0.52, tip_y=0.50),
            hand_present=True,
        )
        self.assertEqual(sample.dx, 0.0)
        self.assertEqual(sample.dy, 0.0)

    def test_tracks_index_tip_not_palm(self) -> None:
        mapper = TouchGatedPointerMapper(
            use_palm_center=False,
            move_deadzone=0.001,
            sensitivity=2000.0,
            use_one_euro=False,
            index_tip_smooth_alpha=1.0,
        )
        mapper.set_output_enabled(True)
        mapper.update(make_hand_landmarks(tip_x=0.50, tip_y=0.50), hand_present=True)
        sample = mapper.update(
            make_hand_landmarks(tip_x=0.55, tip_y=0.50),
            hand_present=True,
        )
        self.assertGreater(sample.dx, 0.0)
        self.assertEqual(sample.dy, 0.0)

    def test_vector_deadzone_keeps_diagonal_motion(self) -> None:
        mapper = TouchGatedPointerMapper(
            move_deadzone=0.008,
            sensitivity=2000.0,
            use_one_euro=False,
            index_tip_smooth_alpha=1.0,
        )
        mapper.set_output_enabled(True)
        mapper.update(make_hand_landmarks(tip_x=0.50, tip_y=0.50), hand_present=True)
        sample = mapper.update(
            make_hand_landmarks(tip_x=0.506, tip_y=0.504),
            hand_present=True,
        )
        self.assertNotEqual(sample.dx, 0.0)
        self.assertNotEqual(sample.dy, 0.0)

    def test_button_held_disables_stationary_lock(self) -> None:
        mapper = TouchGatedPointerMapper(
            move_deadzone=0.0,
            sensitivity=2000.0,
            use_one_euro=False,
            index_tip_smooth_alpha=1.0,
        )
        mapper.set_output_enabled(True)
        mapper.set_button_held(True)
        mapper.update(make_hand_landmarks(), hand_present=True)
        for _ in range(STATIONARY_LOCK_FRAMES + 2):
            sample = mapper.update(
                make_hand_landmarks(tip_x=0.50005, tip_y=0.50005),
                hand_present=True,
            )
        self.assertFalse(sample.stationary_locked)

    def test_reacquire_during_draw_skips_jump(self) -> None:
        mapper = TouchGatedPointerMapper(
            move_deadzone=0.0,
            sensitivity=2000.0,
            use_one_euro=False,
            index_tip_smooth_alpha=1.0,
        )
        mapper.set_output_enabled(True)
        mapper.set_button_held(True)
        mapper.update(make_hand_landmarks(tip_x=0.50, tip_y=0.50), hand_present=True)
        mapper.update(make_hand_landmarks(tip_x=0.55, tip_y=0.50), hand_present=True)
        for _ in range(DRAWING_COAST_FRAMES + 1):
            mapper.update(make_hand_landmarks(), hand_present=False)
        sample = mapper.update(make_hand_landmarks(tip_x=0.60, tip_y=0.50), hand_present=True)
        self.assertEqual(sample.dx, 0.0)
        self.assertEqual(sample.dy, 0.0)

    def test_coast_keeps_motion_while_tracking_blinks(self) -> None:
        mapper = TouchGatedPointerMapper(
            move_deadzone=0.0,
            sensitivity=2000.0,
            use_one_euro=False,
            index_tip_smooth_alpha=1.0,
        )
        mapper.set_output_enabled(True)
        mapper.set_button_held(True)
        mapper.update(make_hand_landmarks(tip_x=0.50, tip_y=0.50), hand_present=True)
        mapper.update(make_hand_landmarks(tip_x=0.55, tip_y=0.50), hand_present=True)
        coast = mapper.update(make_hand_landmarks(), hand_present=False)
        self.assertTrue(coast.hand_ok)
        self.assertEqual(coast.dx, 0.0)

    def test_draw_jump_guard_clamps_tracking_spike(self) -> None:
        mapper = TouchGatedPointerMapper(
            move_deadzone=0.0,
            sensitivity=2000.0,
            use_one_euro=False,
            index_tip_smooth_alpha=1.0,
        )
        mapper.set_output_enabled(True)
        mapper.set_button_held(True)
        mapper.update(make_hand_landmarks(tip_x=0.50, tip_y=0.50), hand_present=True)
        spike = mapper.update(make_hand_landmarks(tip_x=0.80, tip_y=0.80), hand_present=True)
        self.assertEqual(spike.dx, 0.0)
        self.assertEqual(spike.dy, 0.0)

    def test_one_euro_reduces_jitter(self) -> None:
        def total_jitter(mapper: TouchGatedPointerMapper) -> float:
            mapper.set_output_enabled(True)
            mapper.update(make_hand_landmarks(), hand_present=True)
            total = 0.0
            for _ in range(4):
                sample = mapper.update(
                    make_hand_landmarks(tip_x=0.502, tip_y=0.498),
                    hand_present=True,
                )
                total += abs(sample.dx) + abs(sample.dy)
            return total

        raw = TouchGatedPointerMapper(
            move_deadzone=0.0,
            sensitivity=2000.0,
            use_one_euro=False,
            index_tip_smooth_alpha=1.0,
        )
        smooth = TouchGatedPointerMapper(
            move_deadzone=0.0,
            sensitivity=2000.0,
            use_one_euro=True,
            one_euro_min_cutoff=2.5,
            one_euro_beta=0.001,
        )
        self.assertLess(total_jitter(smooth), total_jitter(raw))

    def test_wrist_relative_invariant_to_global_shift(self) -> None:
        landmarks_a = make_hand_landmarks(tip_x=0.50, tip_y=0.50)
        landmarks_b = make_hand_landmarks(tip_x=0.55, tip_y=0.50)
        shift = 0.08
        shifted_a = make_hand_landmarks(
            tip_x=0.50 + shift,
            tip_y=0.50 + shift,
            wrist_x=0.40 + shift,
            wrist_y=0.60 + shift,
            middle_x=0.42 + shift,
            middle_y=0.55 + shift,
        )
        shifted_b = make_hand_landmarks(
            tip_x=0.55 + shift,
            tip_y=0.50 + shift,
            wrist_x=0.40 + shift,
            wrist_y=0.60 + shift,
            middle_x=0.42 + shift,
            middle_y=0.55 + shift,
        )
        rel_a = wrist_relative_point(landmarks_a, use_palm_center=False)
        rel_b = wrist_relative_point(landmarks_b, use_palm_center=False)
        rel_shift_a = wrist_relative_point(shifted_a, use_palm_center=False)
        rel_shift_b = wrist_relative_point(shifted_b, use_palm_center=False)
        delta = (rel_b[0] - rel_a[0], rel_b[1] - rel_a[1])
        delta_shifted = (rel_shift_b[0] - rel_shift_a[0], rel_shift_b[1] - rel_shift_a[1])
        self.assertAlmostEqual(delta[0], delta_shifted[0], places=4)
        self.assertAlmostEqual(delta[1], delta_shifted[1], places=4)
        self.assertGreater(delta[0], 0.0)

    def test_reacquire_recenters_before_motion(self) -> None:
        mapper = TouchGatedPointerMapper(
            move_deadzone=0.0,
            sensitivity=2000.0,
            use_one_euro=False,
            index_tip_smooth_alpha=1.0,
        )
        mapper.set_output_enabled(True)
        present = make_hand_landmarks(tip_x=0.50, tip_y=0.50)
        mapper.update(present, hand_present=True)
        mapper.update(present, hand_present=True)
        mapper.update(make_hand_landmarks(), hand_present=False)
        reacquire = make_hand_landmarks(tip_x=0.60, tip_y=0.50)
        first = mapper.update(reacquire, hand_present=True)
        self.assertEqual(first.dx, 0.0)
        self.assertEqual(first.dy, 0.0)
        second = mapper.update(
            make_hand_landmarks(tip_x=0.62, tip_y=0.50),
            hand_present=True,
        )
        self.assertGreater(second.dx, 0.0)

    def test_adaptive_smoothing_moves_more_on_fast_swipe(self) -> None:
        fixed = TouchGatedPointerMapper(
            move_deadzone=0.0,
            sensitivity=2000.0,
            max_delta_per_frame=500.0,
            use_one_euro=False,
            adaptive_smoothing=False,
            index_tip_smooth_alpha=0.3,
        )
        adaptive = TouchGatedPointerMapper(
            move_deadzone=0.0,
            sensitivity=2000.0,
            max_delta_per_frame=500.0,
            use_one_euro=False,
            adaptive_smoothing=True,
            index_tip_smooth_alpha=0.3,
        )
        for mapper in (fixed, adaptive):
            mapper.set_output_enabled(True)

        def swipe(mapper: TouchGatedPointerMapper) -> float:
            mapper.update(make_hand_landmarks(tip_x=0.50, tip_y=0.50), hand_present=True)
            total = 0.0
            for step in range(6):
                sample = mapper.update(
                    make_hand_landmarks(tip_x=0.50 + 0.02 * (step + 1), tip_y=0.50),
                    hand_present=True,
                )
                total += abs(sample.dx)
            return total

        self.assertGreater(swipe(adaptive), swipe(fixed))

    def test_stationary_lock_zeros_idle_jitter(self) -> None:
        mapper = TouchGatedPointerMapper(
            move_deadzone=0.0,
            sensitivity=2000.0,
            use_one_euro=False,
            index_tip_smooth_alpha=1.0,
        )
        mapper.set_output_enabled(True)
        base = make_hand_landmarks(tip_x=0.50, tip_y=0.50)
        mapper.update(base, hand_present=True)
        sample = base
        total = 0.0
        for _ in range(12):
            sample = mapper.update(base, hand_present=True)
            total += abs(sample.dx) + abs(sample.dy)
        self.assertEqual(total, 0.0)
        self.assertTrue(sample.stationary_locked)


class ReceiveGuardTest(unittest.TestCase):
    def test_accepts_increasing_sequence(self) -> None:
        guard = ReceiveGuard()
        self.assertEqual(guard.accept(100, 1, 1000), (True, None))
        self.assertEqual(guard.accept(100, 2, 1050), (True, None))

    def test_rejects_duplicate_sequence(self) -> None:
        guard = ReceiveGuard()
        self.assertEqual(guard.accept(100, 1, 1000), (True, None))
        self.assertEqual(guard.accept(100, 1, 1010), (False, "duplicate_seq"))

    def test_rejects_stale_timestamp_same_session(self) -> None:
        guard = ReceiveGuard()
        self.assertEqual(guard.accept(100, 5, 500), (True, None))
        self.assertEqual(guard.accept(100, 6, 490), (False, "stale_t_ms"))

    def test_resets_on_session_change(self) -> None:
        guard = ReceiveGuard()
        self.assertEqual(guard.accept(100, 50, 600_000), (True, None))
        ok, reason = guard.accept(200, 1, 5_000)
        self.assertTrue(ok, reason)
        self.assertEqual(guard.session_id, 200)

    def test_resets_on_clock_rewind_same_session(self) -> None:
        guard = ReceiveGuard()
        self.assertEqual(guard.accept(100, 50, 600_000), (True, None))
        ok, reason = guard.accept(100, 1, 1_000)
        self.assertTrue(ok, reason)
        self.assertLess(1_000 + TMS_REWIND_RESET_MS, 600_000)

    def test_manual_reset_clears_state(self) -> None:
        guard = ReceiveGuard()
        guard.accept(100, 1, 1000)
        guard.reset("test")
        self.assertIsNone(guard.last_sequence)
        self.assertEqual(guard.accept(100, 1, 1000), (True, None))


class ScaleMotionTest(unittest.TestCase):
    def test_gain_only(self) -> None:
        dx, dy = scale_motion(
            10,
            -20,
            gain=0.5,
            screen_scale=False,
            screen_width=1920,
            screen_height=1080,
        )
        self.assertEqual((dx, dy), (5, -10))

    def test_screen_scale_uses_glasses_reference(self) -> None:
        dx, dy = scale_motion(
            48,
            64,
            gain=1.0,
            screen_scale=True,
            screen_width=1920,
            screen_height=1080,
        )
        self.assertEqual(dx, int(round(48 * (1920 / GLASSES_REF_WIDTH))))
        self.assertEqual(dy, int(round(64 * (1080 / GLASSES_REF_HEIGHT))))


class SubpixelMotionTest(unittest.TestCase):
    def test_accumulates_fractional_pixels(self) -> None:
        accum = SubpixelAccumulator()
        moves = []
        for _ in range(3):
            moves.append(
                accum.add(
                    0.4,
                    0.0,
                    gain=1.0,
                    screen_scale=False,
                    screen_width=1920,
                    screen_height=1080,
                )
            )
        self.assertEqual(moves[0], (0, 0))
        self.assertEqual(moves[1], (0, 0))
        self.assertEqual(moves[2], (1, 0))
        self.assertAlmostEqual(accum.x, 0.2, places=5)

    def test_reset_clears_remainder(self) -> None:
        accum = SubpixelAccumulator()
        accum.add(0.5, 0.5, gain=1.0, screen_scale=False, screen_width=1920, screen_height=1080)
        accum.reset()
        self.assertEqual(accum.x, 0.0)
        self.assertEqual(accum.y, 0.0)


class LeftButtonHoldTest(unittest.TestCase):
    def setUp(self) -> None:
        settings = SensitivitySettings(0.5, Path(__file__).with_name(".test_settings.json"))
        self.agent = MouseAgent("dev-token", settings, laptop_inference=False)
        self.agent._mouse = MagicMock()
        self.agent.armed = True

    def _decoded(self, *, left: bool, seq: int) -> Decoded:
        return Decoded(
            magic=MAGIC,
            version=1,
            flags=FLAG_OUTPUT_ENABLED,
            session_id=1,
            sequence=seq,
            t_ms=1000 + seq,
            dx=0,
            dy=0,
            buttons=BUTTON_LEFT if left else 0,
            auth_valid=True,
        )

    def test_hold_survives_single_false_packet(self) -> None:
        _, press, _ = self.agent._plan_packet_actions(self._decoded(left=True, seq=1))
        self.assertEqual(press, "press")
        for seq in (2, 3, 4):
            _, mid, _ = self.agent._plan_packet_actions(self._decoded(left=False, seq=seq))
            self.assertIsNone(mid)
            self.assertTrue(self.agent._os_left_down)
        _, release, _ = self.agent._plan_packet_actions(self._decoded(left=False, seq=5))
        self.assertEqual(release, "release")
        self.assertFalse(self.agent._os_left_down)


if __name__ == "__main__":
    unittest.main()
