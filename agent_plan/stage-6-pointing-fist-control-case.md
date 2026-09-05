# Stage 6 — Static Posture Control Case (Dual-Axis + Fist)

Control scheme: **thumb = vertical**, **four fingers = horizontal**, **fist = click**.  
Diagonals = both axes active. Replaces legacy **palm translation + pinch**.

## Control case summary

| Case | Posture | Clutch | Expected output |
|------|---------|--------|-----------------|
| C0 | No hand | any | `LOST` / `IDLE`; dx=0; button released |
| C1 | Four fingers extended, neutral thumb+fingers | off | `IDLE`; no UDP motion |
| C2 | P1 neutral | on | `TRACKING`; dx=0 dy=0 (dead zones) |
| C3a | Thumb **out** vs neutral | on | dy &lt; 0 (up), dx≈0 |
| C3b | Thumb **in** vs neutral (fingers still open) | on | dy &gt; 0 (down), dx≈0; **not** `FIST` |
| C3c | Finger aim left/right vs neutral | on | dx ≠ 0, dy≈0 if thumb neutral |
| C3d | Thumb out + finger aim offset | on | dx ≠ 0 **and** dy ≠ 0 (diagonal) |
| C4 | Return to P1 neutral | on | dx=0 dy=0 within ~1 frame (after smoothing) |
| C5 | Full fist (all fingers + thumb) | on | `FIST`; left down; dx=0 dy=0 |
| C6 | Open hand after fist | on | left up after cooldown; resume pointing |
| C7 | Hand loss while pressed | on→off | button released; no stuck click |

## User posture (demo)

1. Hand in mid-air control zone; **four fingers extended**.
2. TouchPad **click** → clutch on → **P1 neutral** captured (finger angle + thumb spread).
3. **Thumb out** → cursor up; **thumb toward palm** → cursor down (fingers stay open).
4. **Rotate wrist** → cursor left/right.
5. Combine thumb + wrist → **diagonal**.
6. **Full fist** → left click; open hand → release.

## Model: dual-axis joystick

```text
on clutch ON:
  neutralFingerAngle = atan2(four-finger aim vector)
  neutralThumbSpread = dist(thumbTip, palmCenter) / handScale

each frame:
  dx = f( fingerAngle - neutralFingerAngle )   // horizontal dead zone ~15°
  dy = g( thumbSpread - neutralThumbSpread )   // vertical dead band ~0.06 spread

  apply pointingFlipX / pointingFlipY
  fist → suppress dx/dy, leftPressed=true
```

Fist: all finger curls + thumb tuck (`FistClassifier`), hysteresis + cooldown.

## Config (`HandMouseConfig`)

| Field | Role |
|-------|------|
| `controlScheme = POINTING_FIST` | Default |
| `pointingDeadzoneDeg` / `pointingMaxOffsetDeg` | Horizontal |
| `thumbVerticalDeadzone` / `thumbVerticalMaxDelta` | Vertical |
| `thumbSmoothing` / `pointingSmoothing` | Filter |
| `TRANSLATION_PINCH` | Legacy |

## TouchPad key codes (app navigation)

See full table in [`stage-6-hand-posture-guide.md` §8](stage-6-hand-posture-guide.md).

| Hub gesture | KeyEvent | Code |
|-------------|----------|------|
| Enter scene | `KEYCODE_ENTER` | 66 |
| Exit app | `KEYCODE_BACK` | 4 |
| Next item | `KEYCODE_DPAD_RIGHT` | 22 |
| Prev item | `KEYCODE_DPAD_LEFT` | 21 |
| Long press | `KEYCODE_PROG_BLUE` | 186 |

Hand-mouse scene: **click** = clutch; **double-click** = back; **long press** = save link.

## Files

| File | Role |
|------|------|
| `hand/HandPoseMath.kt` | Finger aim, thumb spread, validity |
| `hand/FistClassifier.kt` | Fist detect |
| `hand/PointingController.kt` | Dual-axis dx/dy |
| `hand/PointerMapper.kt` | Scheme switch |
| `input/TouchPadSwipeDetector.kt` | DPAD → swipe events |

UDP / laptop agent unchanged (`dx`, `dy`, `leftPressed`).

## Hardware exit gate

- [ ] C2: P1 neutral 5 s — no drift
- [ ] C3a–C3c: each axis independent
- [ ] C3d: diagonal (thumb out + aim left) works
- [ ] C3b vs C5: thumb-in move ≠ fist click
- [ ] C5–C7: fist reliable; no stuck button
- [ ] Hub: DPAD swipe cycles items; click enter; double-click exit

See [`stage-6-pointing-fist-log.md`](stage-6-pointing-fist-log.md).

**Posture diagrams:** [`stage-6-hand-posture-guide.md`](stage-6-hand-posture-guide.md)
