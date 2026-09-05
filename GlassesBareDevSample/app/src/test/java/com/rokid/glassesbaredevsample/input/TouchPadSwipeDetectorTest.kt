package com.rokid.glassesbaredevsample.input

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TouchPadSwipeDetectorTest {
    private val down = KeyEvent.ACTION_DOWN

    @Test
    fun slowRightSwipe_emitsSwipeForward() {
        val detector = TouchPadSwipeDetector()
        assertEquals(
            BareKeyEvent.SwipeForward,
            detector.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT, down, 0),
        )
        assertNull(detector.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT, down, 0))
    }

    @Test
    fun slowLeftSwipe_emitsSwipeBack() {
        val detector = TouchPadSwipeDetector()
        assertEquals(
            BareKeyEvent.SwipeBack,
            detector.onKeyDown(KeyEvent.KEYCODE_DPAD_LEFT, down, 0),
        )
    }

    @Test
    fun fastRightSwipe_emitsOnce() {
        val detector = TouchPadSwipeDetector()
        assertEquals(
            BareKeyEvent.SwipeForward,
            detector.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT, down, 0),
        )
        assertNull(detector.onKeyDown(KeyEvent.KEYCODE_DPAD_DOWN, down, 0))
    }

    @Test
    fun fastLeftSwipe_emitsOnce() {
        val detector = TouchPadSwipeDetector()
        assertEquals(
            BareKeyEvent.SwipeBack,
            detector.onKeyDown(KeyEvent.KEYCODE_DPAD_LEFT, down, 0),
        )
        assertNull(detector.onKeyDown(KeyEvent.KEYCODE_DPAD_UP, down, 0))
    }

    @Test
    fun loneDownWithoutRight_isIgnored() {
        val detector = TouchPadSwipeDetector()
        assertNull(detector.onKeyDown(KeyEvent.KEYCODE_DPAD_DOWN, down, 0))
    }
}
