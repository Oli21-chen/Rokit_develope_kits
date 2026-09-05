package com.rokid.glassesbaredevsample.input

/**
 * Sample 统一 UI 语义。
 *
 * 事件定义见开发者文档「按键与佩戴和折叠」专章。
 *
 * Hub 导航（默认）：
 * - [SwipeForward] / [SwipeBack]：TouchPad 单指前/后滑（`KEYCODE_DPAD_RIGHT/LEFT` 或快速滑 `+ DOWN/UP`）→ 切换功能项；部分固件亦可能走双指有序广播
 * - [Click]：TouchPad 单指单击 → 进入当前项
 * - [DoubleClick]：TouchPad 单指双击 → 退出（Hub 关闭应用 / 子页返回）
 *
 * - [Click]：`KEYCODE_ENTER` 与镜腿单击等价。
 * - [LongPress]：TouchPad 单指长按 · `KEYCODE_PROG_BLUE` 或 `ACTION_AI_START`。
 */
enum class BareKeyEvent {
    Click,
    DoubleClick,
    LongPress,
    /** TouchPad long-press released · `KEYCODE_PROG_BLUE` up (Plan D posture freeze end). */
    LongPressRelease,
    SwipeForward,
    SwipeBack,
    /** TouchPad two-finger single tap (Solution A: disable clutch). */
    TwoFingerSingleTap,
}
