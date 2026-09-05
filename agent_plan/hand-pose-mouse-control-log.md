# Hand-Pose Mouse: Decision and Revision Log

Companion log for [`hand-pose-mouse-control.md`](./hand-pose-mouse-control.md).

## Decision log

| Date | Decision | Evidence |
|------|----------|----------|
| 2026-07-26 | Start with one-hand relative motion, left pinch, TouchPad clutch, and a UDP/Python companion | Lowest-risk vertical slice; matches the current CameraX and input sample |
| 2026-07-26 | Stage 1 CameraX analysis gate **PASS**; proceed to Stage 2 MediaPipe on-device | Hardware HUD: 480×640 YUV format 35, rotation 270°, ~29.7 arrival/analyzed FPS, ~5 estimated drops @30fps, ~0.1 ms analysis (no ML), errors 0; user confirmed 10-minute run and rebind/clutch behavior |
| 2026-07-26 | Keep Stage 1 analysis resolution/orientation as the Stage 2 input baseline | Measured stream is 480×640 @ 270° on RG-glasses; do not change CameraX target until MediaPipe FPS/thermal results force a change |
| 2026-07-26 | Pin MediaPipe Tasks Vision **0.10.26** + float16 `hand_landmarker.task` v1; Stage 2 CPU/VIDEO/one-hand | Official Google model URL; Apache-2.0 model bundle; SHA-256 recorded in stage-2 log |
| 2026-07-26 | Stage 2 code installed on RG-glasses; hardware exit gate still open | Unit tests + assembleDebug PASS; 10-minute MediaPipe checklist pending |
| 2026-07-26 | Apply `HandDisplayTransform` +270° CW (90° CCW) after MediaPipe upright for HUD/control | Hardware: hand up drew pointing right; CameraX still reports 270° — keep MP rotation, correct display axis only |
| 2026-07-26 | Stage 2 MediaPipe gate **PASS**; proceed to Stage 3 local gesture/pointer | User confirmed Stage 2 perfect (detection, overlay, display axis) |
| 2026-07-26 | No posture settings UI; document mid-air control-zone + clutch workflow | Camera FOV requires deliberate pose; relative mode needs no calibration pose |
| 2026-07-26 | Stage 3 local palm-center move + pinch + clutch shipped (hardware gate open) | Unit tests PASS; installed on RG-glasses |
| 2026-07-26 | Stage 4: UDP binary v1 + Python/pynput companion; HMAC token; ADB Intent config; agent arm/disarm | Matches main plan Stage 4; details in [`stage-4-udp-companion-log.md`](./stage-4-udp-companion-log.md) |
| 2026-07-26 | Stage 4 code installed on glasses; hardware same-LAN gate open | Unit tests PASS; companion under `companion/mouse_agent/` |
| 2026-07-27 | Stage 5a link persist in daily use; agent reconnect + sensitivity GUI shipped | Saved host loads from launcher; ReceiveGuard reset on glasses reboot; `start_mouse_agent.bat` + gain slider maps 480×640 → laptop screen |

## Revision history

| Date | Change |
|------|--------|
| 2026-07-26 | Evaluated the plan; narrowed the MVP; added hardware reconnaissance, measurable exit gates, frame ownership, camera transforms, gesture hysteresis, fail-safe UDP semantics, tests, configuration concerns, and a decision log |
| 2026-07-26 | Moved decision/revision records and implementation prompts into dedicated companion documents |
| 2026-07-26 | Recorded Stage 1 hardware PASS; opened Stage 2 execution log in [`stage-2-mediapipe-log.md`](./stage-2-mediapipe-log.md) |
| 2026-07-26 | Implemented Stage 2 MediaPipe wiring; waiting on 10-minute hardware benchmark results |
| 2026-07-26 | Stage 2 closed **PASS**; next is Stage 3 local control |
| 2026-07-26 | Opened Stage 3 log with demo posture; local pointer implementation installed |
| 2026-07-26 | Prepared Stage 4 UDP companion execution log (packet v1, fail-safe, run steps) |
| 2026-07-26 | Implemented Stage 4 UDP client + Python mouse agent; awaiting same-LAN hardware |
| 2026-07-27 | Stage 5a persist + agent UX polish documented in `test_usage.md` | Bat launcher, Arm button, sensitivity slider, reconnect-safe guard |

## Log maintenance

- Record architecture or scope choices in **Decision log**, including the evidence or measurement that justified them.
- Record meaningful plan changes in **Revision history**.
- Keep Stage 1 implementation-session details in [`developing_step_logs.md`](./developing_step_logs.md).
- Keep Stage 2+ session details in the dedicated stage log files under `agent_plan/` ([`stage-2-mediapipe-log.md`](./stage-2-mediapipe-log.md), [`stage-3-local-control-log.md`](./stage-3-local-control-log.md), [`stage-4-udp-companion-log.md`](./stage-4-udp-companion-log.md)).
