# Stage 6 — Pointing + Fist (implementation log)

Execution log for static posture control. Control cases: [`stage-6-pointing-fist-control-case.md`](stage-6-pointing-fist-control-case.md).

## Goal

Replace palm translation + pinch with **dual-axis static posture** (thumb vertical, four-finger horizontal, fist click) and minimal hand translation.

## Implemented (2026-07-27)

| File | Change |
|------|--------|
| `hand/HandPoseMath.kt` | Finger aim, thumb spread, extension helpers |
| `hand/FistClassifier.kt` | Fist hysteresis |
| `hand/PointingController.kt` | Dual-axis dx/dy; neutral finger + thumb calib |
| `hand/HandMouseConfig.kt` | `thumbVerticalDeadzone`, `thumbVerticalMaxDelta` |
| `hand/PointerMapper.kt` | `POINTING_FIST` scheme |
| `HandMouseScreen.kt` | HUD: 四指/拇指/握拳 |
| `input/TouchPadSwipeDetector.kt` | DPAD 21/22/19/20 → SwipeForward/Back |
| `MainActivity.kt` | Routes ENTER/BACK/PROG_BLUE/DPAD keys |
| `PointerControlTest.kt` | Pointing + fist + dual-axis tests |

**Docs:** [`stage-6-hand-posture-guide.md`](stage-6-hand-posture-guide.md) (P0–P8 + key codes §8).

## Default config

- Horizontal dead zone: 15°; max offset 40°
- Vertical thumb dead band: 0.06 spread; max delta 0.16
- `pointingSensitivity`: 85
- Fist on/off curl: 0.42 / 0.52

Legacy: `controlScheme = TRANSLATION_PINCH`.

## Hardware checklist

- [ ] P1 neutral → no drift 5 s
- [ ] Thumb out/in → up/down; wrist → left/right; diagonal combined
- [ ] Thumb-in (P3) ≠ fist (P5) for click
- [ ] Fist → click; open → release
- [ ] Hub: DPAD swipe, ENTER enter, BACK exit
- [ ] Axis flip: `pointingFlipX/Y` if inverted on laptop

## Status

**INSTALLED — TRY ON DEVICE.** Tune `pointingSensitivity` / laptop gain slider after first run.
