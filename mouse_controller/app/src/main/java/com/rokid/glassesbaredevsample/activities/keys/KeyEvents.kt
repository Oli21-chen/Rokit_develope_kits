package com.rokid.glassesbaredevsample.activities.keys

/** TouchPad / temple bar broadcast actions used by [com.rokid.glassesbaredevsample.input.BareGlassesInputDispatcher]. */
enum class KeyEventAction(val action: String) {
    CLICK("com.android.action.ACTION_SPRITE_BUTTON_CLICK"),
    BUTTON_DOWN("com.android.action.ACTION_SPRITE_BUTTON_DOWN"),
    BUTTON_UP("com.android.action.ACTION_SPRITE_BUTTON_UP"),
    DOUBLE_CLICK("com.android.action.ACTION_SPRITE_BUTTON_DOUBLE_CLICK"),
    AI_START("com.android.action.ACTION_AI_START"),
    LONG_PRESS("com.android.action.ACTION_SPRITE_BUTTON_LONG_PRESS"),
    TWO_FINGER_SINGLE("com.android.action.ACTION_TWO_FINGER_SINGLE_TAP"),
    TWO_FINGER_DOUBLE("com.android.action.ACTION_TWO_FINGER_DOUBLE_TAP"),
    SWIPE_FORWARD("com.android.action.ACTION_TWO_FINGER_SWIPE_FORWARD"),
    SWIPE_BACK("com.android.action.ACTION_TWO_FINGER_SWIPE_BACK"),
    SETTINGS_KEY("com.android.action.ACTION_SETTINGS_KEY"),
}

fun KeyEventAction.logLabel(): String = when (this) {
    KeyEventAction.CLICK -> "广播·镜腿单击"
    KeyEventAction.BUTTON_DOWN -> "广播·镜腿按下"
    KeyEventAction.BUTTON_UP -> "广播·镜腿抬起"
    KeyEventAction.DOUBLE_CLICK -> "广播·镜腿双击"
    KeyEventAction.AI_START -> "广播·TouchPad长按"
    KeyEventAction.LONG_PRESS -> "广播·镜腿长按"
    KeyEventAction.TWO_FINGER_SINGLE -> "广播·双指单击"
    KeyEventAction.TWO_FINGER_DOUBLE -> "广播·双指双击"
    KeyEventAction.SWIPE_FORWARD -> "广播·双指前滑"
    KeyEventAction.SWIPE_BACK -> "广播·双指后滑"
    KeyEventAction.SETTINGS_KEY -> "广播·双指长按"
}

fun keyEventActionLabel(action: String): String =
    KeyEventAction.entries.firstOrNull { it.action == action }?.logLabel()
        ?: action.substringAfterLast('.')
