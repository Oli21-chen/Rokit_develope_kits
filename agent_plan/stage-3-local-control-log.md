# Stage 3 — Local Gesture and Pointer Control

Execution log for Stage 3 of [`hand-pose-mouse-control.md`](./hand-pose-mouse-control.md). Stage 2 PASS notes: [`stage-2-mediapipe-log.md`](./stage-2-mediapipe-log.md).

## Goal

Map one-hand landmarks (display space) to **local** relative pointer motion + thumb–index left click, with TouchPad clutch. No UDP / laptop yet.

## Demo posture (how to use)

There is **no posture settings UI**. Use this control-zone habit:

1. Wear glasses; look roughly forward.
2. Raise **one hand** into the forward camera cone (upper chest / in front of face — not desk level).
3. Palm toward the camera until the skeleton looks upright and stable.
4. **Clutch off** by default — rest your hand freely.
5. TouchPad **click** → clutch **on** → small mid-air moves act like a trackpad; **thumb–index pinch** = left click.
6. Click again to clutch **off** before lowering the hand.
7. If the skeleton disappears, output freezes and the button releases — bring the hand back into view, then continue.

Do **not** aim at the laptop screen. Relative mode only cares about hand motion in the control zone.

## Scope

1. `HandMouseConfig` defaults (pinch hysteresis, deadzone, smoothing, sensitivity).
2. `GestureClassifier` — normalized pinch on/off, hold, cooldown, hand-loss reset.
3. `PointerMapper` — palm-center (default) relative Δx/Δy → `PointerCommand`.
4. ViewModel applies mapper only when clutch enabled; HUD shows gesture, dx/dy, local cursor, pinch counters.
5. Keep MediaPipe path + landmark overlay; no `INTERNET`, no companion.

## Exit gate

- Pinch: no stuck left button; false clicks target &lt;2% on a deliberate 100-pinch run.
- Hand loss freezes motion and releases button immediately (same frame / &lt;150 ms).
- With clutch on and head still, cursor on HUD feels controllable; note head-motion interference for Stage 5.

## Implementation checklist

- [x] Config + GestureClassifier + PointerMapper + PointerCommand
- [x] ViewModel/HUD wiring + local cursor
- [x] Unit tests
- [x] Build/install
- [ ] Hardware checklist

## Hardware checklist

- [ ] Enter **手势鼠标 · Stage 3**; MediaPipe ready; clutch starts **paused**.
- [ ] Raise hand in control zone; skeleton upright; crosshair visible at center.
- [ ] Click → clutch on → move hand → crosshair tracks (TRACKING).
- [ ] Thumb–index pinch → `PINCH` / 左键按下; release → 抬起; ↓↑ counters increment.
- [ ] Lower hand out of view → `LOST`, cursor freezes, left released.
- [ ] Click clutch off → IDLE; motion stops even if hand moves.
- [ ] Double-click returns; re-enter starts clutch off.
- [ ] Optional: ~20 deliberate pinches — note false clicks / stuck button.

## Development session log

### 2026-07-26 — Stage 3: local pointer + pinch + clutch

- Goal: Stage 3 local control with demo posture documented.
- Files:
  - `agent_plan/stage-3-local-control-log.md` (posture + checklist)
  - `hand/HandMouseConfig.kt`, `PointerCommand.kt`, `GestureClassifier.kt`, `PointerMapper.kt`
  - `ui/handmouse/LocalPointerCursor.kt`
  - `activities/handmouse/HandMouseScreen.kt`, `HandMouseViewModel.kt`
  - `activities/main/HubScreen.kt` — Stage 3 label
  - `app/src/test/.../hand/PointerControlTest.kt`
- Behavior:
  - display-space landmarks → palm-center relative dx/dy + pinch hysteresis;
  - clutch via TouchPad click; local crosshair; pinch ↓↑ counters; no network.
- Build/test: **PASS**; installed on RG-glasses.
- Hardware: **PENDING** user confirmation.
- Result: **PARTIAL**

## Status

**INSTALLED — AWAITING HARDWARE.** Use the demo posture above; confirm checklist, then implement Stage 4 per [`stage-4-udp-companion-log.md`](./stage-4-udp-companion-log.md).
