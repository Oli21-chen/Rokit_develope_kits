#!/usr/bin/env python3
"""Unit tests for mouse_agent ReceiveGuard reconnect behaviour."""

import unittest

from mouse_agent import (
    GLASSES_REF_HEIGHT,
    GLASSES_REF_WIDTH,
    ReceiveGuard,
    TMS_REWIND_RESET_MS,
    scale_motion,
)


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


if __name__ == "__main__":
    unittest.main()
