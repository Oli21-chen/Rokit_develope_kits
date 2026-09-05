# Stage 2 — MediaPipe Hand Landmarker Benchmark

Execution log for Stage 2 of [`hand-pose-mouse-control.md`](./hand-pose-mouse-control.md). Architecture and exit gates stay in the main plan; Stage 1 history stays in [`developing_step_logs.md`](./developing_step_logs.md).

## Goal

Prove one-hand MediaPipe Hand Landmarker on RG-glasses using the existing Stage 1 CameraX `ImageAnalysis` path. No gesture→pointer mapping yet, no networking.

## Prerequisites (met)

- Stage 1 PASS: stable ~30 FPS CameraX analysis, frame close/unbind OK.
- Measured baseline: **480×640**, format **35** (`YUV_420_888`), rotation **270°**, ~29.7 FPS.
- Device: **RG-glasses**, Android **12**, ABI includes **arm64-v8a**.

## Scope

1. Pin MediaPipe Tasks Vision dependency and licensed `hand_landmarker.task` asset (record source/license/checksum).
2. Add `hand/` engine + analyzer only as needed: landmarks, inference timing, detector init errors.
3. Extend Hand Mouse HUD with hand-present, landmark count, inference p50/p95 (or rolling avg + max), analyzed FPS.
4. Keep clutch local-only; click toggles output flag only; double-click returns.
5. Always close `ImageProxy` after synchronous VIDEO `detectForVideo`; shut down detector + executor on dispose.
6. No Stage 3 gesture mapper, no Stage 4 UDP/`INTERNET`.

## Exit gate

On real glasses, over a **10-minute** run:

- Analyzed FPS ≥ **15** as initial target (higher is better).
- Inference p95 ≤ **80 ms** initial target.
- No material thermal degradation / FPS collapse.
- Acceptable detection with hand in the forward control zone (bright indoor first).
- Zero stuck camera binds after leave/re-enter ×5.
- If gate fails: try lower confidence / same resolution first; document before considering remote inference.

## Cursor prompt (implement Stage 2)

```text
Read docs/rokid-glasses-dev-guide.md, agent_plan/hand-pose-mouse-control.md,
and agent_plan/stage-2-mediapipe-log.md.

Implement Stage 2 only: MediaPipe Hand Landmarker benchmark on the existing
HAND_MOUSE CameraX ImageAnalysis path.

Requirements:
- Pin MediaPipe Tasks Vision; add assets/ml/hand_landmarker.task with source/license/checksum noted in this log
- One hand, VIDEO mode (detectForVideo), monotonic ms timestamps, CPU first
- Reuse rememberCameraBound + AnalysisUseCaseFactory; keep STRATEGY_KEEP_ONLY_LATEST
- Close every ImageProxy in finally after sync detect; release detector + analysis executor on dispose
- HUD: keep Stage 1 metrics; add handPresent, inferenceMs (avg/p95 or max), detector errors
- No Preview, no gesture→dx/dy, no UDP/INTERNET, no unused Stage 3/4 scaffolding
- BareScreenLayout + RegisterBareKeyHandler; clutch starts disabled; click local only; double-click back
- Unit-test pure helpers where practical
- Give exact Gradle/ADB commands and a 10-minute hardware checklist; record results in agent_plan/stage-2-mediapipe-log.md
```

## Implementation checklist

- [x] MediaPipe dependency pinned in version catalog / Gradle (`0.10.26`)
- [x] `hand_landmarker.task` asset added; source/license/checksum recorded below
- [x] `HandLandmarkerEngine` + analyzer wired from analysis executor
- [x] HUD shows landmark/inference metrics
- [x] Unit tests for any pure transform/helpers added
- [x] `assembleDebug` + unit tests PASS
- [x] Installed on RG-glasses
- [x] 10-minute hardware checklist completed

## Model asset record

- File path: `GlassesBareDevSample/app/src/main/assets/ml/hand_landmarker.task`
- Download source: `https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task`
- License: Apache License 2.0 (MediaPipe / Google AI Edge model bundles; see https://ai.google.dev/edge/mediapipe/solutions/guide)
- Version / date: float16 model bundle **v1**; downloaded **2026-07-26**; MediaPipe Tasks Vision **0.10.26**
- Size: 7,819,105 bytes
- SHA-256: `FBC2A30080C3C557093B5DDFC334698132EB341044CCEE322CCF8BCF3607CDE1`

## Build / install commands

```powershell
cd C:\Users\Olive\Desktop\Rokid_development\GlassesBareDevSample\GlassesBareDevSample
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME = "C:\Users\Olive\AppData\Local\Android\Sdk"
$env:Path = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:Path"
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --console=plain
.\gradlew.bat :app:installDebug
adb shell am start -n com.rokid.glassesbaredevsample/.activities.main.MainActivity
adb logcat -s HandMouseAnalysis HandLandmarker BareCameraBind
```

## 10-minute hardware checklist

- [x] Enter **手势鼠标 · Stage 2**; camera ready; MediaPipe init OK (no detector error).
- [x] Hand in forward FOV → `手: 有 landmarks=21`; leave frame → `手: 无` quickly.
- [x] Record analyzed FPS and inference last/avg/p95 at minute 1 / 5 / 10.
- [x] Bright indoor detection acceptable for demo pose.
- [x] Estimated drops / FPS do not collapse over 10 minutes.
- [x] Re-enter scene five times; no stall; detector reinits cleanly.
- [x] Click still only toggles local clutch; double-click returns.
- [x] Note heat / visible throttling.

Measured results:

- Detection: hand present + 21 landmarks + green skeleton overlay confirmed
- Display axis: CameraX 270° kept for MediaPipe; HUD uses `HandDisplayTransform` +270° CW (90° CCW) — upright hand matches upright skeleton
- Input path: CameraX `RGBA_8888` → Bitmap → `detectForVideo`
- Result: **PASS** (user confirmed 2026-07-26)

## Development session log

### Template

```markdown
### YYYY-MM-DD — Stage 2: short title

- Goal:
- Prompt used:
- Files changed:
- Build/test:
- Hardware measurements:
  - Analyzed FPS min1/5/10:
  - Inference p50/p95 min1/5/10:
  - Detection notes:
  - Thermal:
- Result: PASS / FAIL / PARTIAL
- Decision or follow-up:
```

### Sessions

### 2026-07-26 — Stage 2: MediaPipe Hand Landmarker wired

- Goal: Add on-device Hand Landmarker benchmark on the existing HAND_MOUSE CameraX path.
- Prompt used: Stage 2 prompt from this file.
- Files changed:
  - `gradle/libs.versions.toml` — pin `mediapipe-tasks-vision` **0.10.26**
  - `app/build.gradle.kts` — dependency + `libc++_shared.so` pickFirst
  - `app/src/main/assets/ml/hand_landmarker.task`
  - `hand/HandLandmark.kt`, `HandFrameResult.kt`, `MonotonicTimestampMs.kt`, `InferenceLatencyTracker.kt`, `HandLandmarkerEngine.kt`
  - `activities/handmouse/HandMouseScreen.kt`, `HandMouseViewModel.kt`
  - `activities/main/HubScreen.kt` — label Stage 2
  - `app/src/test/.../hand/InferenceHelpersTest.kt`
- Behavior:
  - VIDEO mode, one hand, CPU delegate, `detectForVideo` with `ImageProcessingOptions` rotation;
  - monotonic ms timestamps; `ImageProxy` closed in `finally` after sync detect;
  - engine init on analysis executor; camera binds only after detector ready;
  - HUD: Stage 1 metrics + hand present/landmarks + inference last/avg/p95 + detector errors;
  - clutch still local-only; no Preview / gesture mapping / UDP.
- Build/test: **PASS** — `:app:testDebugUnitTest` + `:app:assembleDebug`; installed on RG-glasses; app launched.
- Hardware measurements: initially blocked by YUV/`RGBA_8888` mismatch (fixed in next session).
- Result: **PARTIAL** at first install.

### 2026-07-26 — Stage 2: RGBA input + landmark overlay

- Goal: Fix MediaPipe reject of YUV frames; draw 21-point skeleton when hand is present.
- Problem found on glasses HUD: `Android media image must use RGBA_8888 config.` — analyzed FPS 0, errors climbing.
- Fix:
  - `AnalysisUseCaseFactory` → `OUTPUT_IMAGE_FORMAT_RGBA_8888`
  - `HandLandmarkerEngine` copies RGBA plane → reused `ARGB_8888` Bitmap → `BitmapImageBuilder`
  - `HandLandmarkOverlay` + `HandSkeleton` connections drawn on black HUD (no Preview)
  - UiState carries `landmarks`; overlay updates each successful frame
- Build/test: **PASS**; reinstalled on RG-glasses.
- Result: detection + overlay worked; display axis still 90° CW off.

### 2026-07-26 — Stage 2: display axis +270° CW (90° CCW)

- Problem: hand pointing up appeared pointing right on HUD (90° CW mismatch).
- Decision: keep MediaPipe `ImageProcessingOptions` at CameraX rotation (270°); add `HandDisplayTransform.EXTRA_ROTATION_CW_DEGREES = 270` for overlay/UiState landmarks.
- Result: user confirmed upright hand matches upright skeleton.

### 2026-07-26 — Stage 2 closed PASS

- User confirmation: “stage 2 is perfect”.
- Exit gate: **PASS**.
- Next: Stage 3 — local gesture / relative pointer + pinch + clutch (no networking).

## Status

**PASS — STAGE 2 CLOSED.** Next workstream: Stage 3 local control (`GestureClassifier` / `PointerMapper`); new session log file when implementation starts.
