package com.rokid.glassesbaredevsample.input

import android.view.KeyEvent

/**
 * Maps Rokid TouchPad swipe KeyEvents to [BareKeyEvent] swipe semantics.
 *
 * On RG-class firmware, single-finger swipes are delivered as DPAD keys (not the
 * `ACTION_TWO_FINGER_SWIPE_*` ordered broadcasts):
 * - Slow right/left: repeated [KeyEvent.KEYCODE_DPAD_RIGHT] / [KeyEvent.KEYCODE_DPAD_LEFT]
 * - Fast right: RIGHT then [KeyEvent.KEYCODE_DPAD_DOWN]
 * - Fast left: LEFT then [KeyEvent.KEYCODE_DPAD_UP]
 *
 * See Rokid system docs: TP-右滑 / TP-左滑 / TP-快速右滑 / TP-快速左滑.
 */
class TouchPadSwipeDetector(
    private val gestureWindowMs: Long = 350L,
    private val repeatCooldownMs: Long = 250L,
) {
    private var windowStartMs = 0L
    private var lastSwipeAtMs = 0L
    private var sawRight = false
    private var sawLeft = false
    private var emittedInWindow = false

    fun onKey(event: KeyEvent): BareKeyEvent? =
        onKeyDown(event.keyCode, event.action, event.repeatCount)

    fun onKeyDown(keyCode: Int, action: Int, repeatCount: Int): BareKeyEvent? {
        if (action != KeyEvent.ACTION_DOWN || repeatCount > 0) return null
        val now = System.currentTimeMillis()
        if (now - windowStartMs > gestureWindowMs) {
            resetWindow(now)
        }
        if (emittedInWindow || now - lastSwipeAtMs < repeatCooldownMs) return null

        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> sawRight = true
            KeyEvent.KEYCODE_DPAD_LEFT -> sawLeft = true
        }

        val swipe = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> BareKeyEvent.SwipeForward
            KeyEvent.KEYCODE_DPAD_LEFT -> BareKeyEvent.SwipeBack
            KeyEvent.KEYCODE_DPAD_DOWN -> if (sawRight) BareKeyEvent.SwipeForward else null
            KeyEvent.KEYCODE_DPAD_UP -> if (sawLeft) BareKeyEvent.SwipeBack else null
            else -> null
        } ?: return null

        emittedInWindow = true
        lastSwipeAtMs = now
        return swipe
    }

    private fun resetWindow(now: Long) {
        windowStartMs = now
        sawRight = false
        sawLeft = false
        emittedInWindow = false
    }
}
