# Hand-Pose Mouse: Development Steps and Agent Prompts

Execution companion for [`hand-pose-mouse-control.md`](./hand-pose-mouse-control.md). Keep implementation milestones, reusable prompts, hardware results, and development-session notes here. Keep architecture and acceptance gates in the main plan.

## Stage 1 — First code milestone

Smallest useful change:

1. Complete stage 0 and save the device/camera facts in the main plan.
2. Add `AnalysisUseCaseFactory`, `AnalysisMetrics`, and a `HandMouseScreen` status HUD.
3. Add route/hub wiring only; do not create MediaPipe or link scaffolding yet.
4. Prove rotation, frame closure, camera unbind, executor cleanup, and the 10-minute camera run on glasses.

### Cursor prompt

```text
Read docs/rokid-glasses-dev-guide.md and agent_plan/hand-pose-mouse-control.md.
Implement Stage 1 only: CameraX ImageAnalysis bound through existing rememberCameraBound,
hub scene HAND_MOUSE, and AnalysisMetrics for dimensions, rotation, arrival/analyzed FPS,
dropped-frame estimate, and analyzer errors. Always close ImageProxy and clean up the executor.
No MediaPipe, network, or unused future scaffolding.
Follow BareScreenLayout + RegisterBareKeyHandler. Output/clutch starts disabled;
click only changes local clutch state and double-click returns.
Add unit tests for coordinate transforms/metrics where practical and provide the exact
Gradle/ADB commands plus a 10-minute hardware checklist.
```

## Development session log

Add one entry after each implementation or hardware-test session.

### 2026-07-26 — Stage 1: CameraX analysis telemetry

- Goal: Add a hand-mouse hub scene that binds `ImageAnalysis` without MediaPipe/networking and reports frame telemetry.
- Files changed:
  - `camera/AnalysisUseCaseFactory.kt`
  - `camera/AnalysisMetrics.kt`
  - `camera/CameraFrameTransform.kt`
  - `activities/handmouse/HandMouseScreen.kt`
  - `activities/handmouse/HandMouseViewModel.kt`
  - `navigation/BareSceneRoutes.kt`
  - `activities/main/HubScreen.kt`
  - `activities/main/MainActivity.kt`
  - `app/src/test/.../camera/AnalysisMetricsTest.kt`
  - `app/src/test/.../camera/CameraFrameTransformTest.kt`
  - `docs/rokid-glasses-dev-guide.md`
- Behavior:
  - binds 640×480-requested YUV `ImageAnalysis` with `STRATEGY_KEEP_ONLY_LATEST`;
  - analyzes on a dedicated single-thread executor;
  - closes every `ImageProxy` in `finally`;
  - displays dimensions, format, rotation, arrival/analyzed FPS, frame counts, estimated drops at nominal 30 FPS, analysis duration, and analyzer errors;
  - output/clutch starts disabled; click toggles local state only; double-click returns;
  - clears the analyzer and shuts down its executor on scene disposal.
- Local verification:
  - IDE diagnostics: PASS (no reported linter errors).
  - Gradle unit tests/build: PASS (2026-07-26) after setting Studio JBR + SDK platform-tools.
  - Hardware test: PASS (2026-07-26) — see measured results below.
- Build/test commands:

```powershell
cd C:\Users\Olive\Desktop\Rokid_development\GlassesBareDevSample\GlassesBareDevSample
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME = "C:\Users\Olive\AppData\Local\Android\Sdk"
$env:Path = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:Path"
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --console=plain
.\gradlew.bat :app:installDebug
adb shell am start -n com.rokid.glassesbaredevsample/.activities.main.MainActivity
adb logcat -s HandMouseAnalysis BareCameraBind
```

#### 10-minute hardware checklist

- [x] `adb devices` shows the glasses in `device` state.
- [x] Enter **手势鼠标 · Stage 1** and grant camera permission.
- [x] Camera status becomes `CameraX 已就绪`; dimensions and format become non-zero.
- [x] Record actual width × height, format, and rotation below.
- [x] Arrival/analyzed FPS become stable; analyzed count continues increasing.
- [x] Estimated drops do not increase rapidly during an idle 10-minute run.
- [x] Analyzer error count remains zero.
- [x] Click toggles only `已暂停` / `已启用（仅本地）`; no network or mouse action occurs.
- [x] Double-click returns to the hub.
- [x] Re-enter the scene five times; camera rebinds and frame counts restart without a stall.
- [x] Leave while output is enabled; re-enter and confirm output starts disabled.
- [x] Run for 10 minutes and note FPS at minute 1, 5, and 10 plus visible heat/throttling.

Measured results:

- Device/model: RG-glasses (Android 12, `SKQ1.240613.001`, ABI `arm64-v8a,armeabi-v7a,armeabi`)
- Actual frame size/format/rotation: **480×640**, format **35** (`YUV_420_888`), rotation **270°**
- FPS (HUD sample during run): arrival **29.7** / analyzed **29.7**; counts matched (e.g. 2564/2564)
- Estimated drops @30fps: **5** (low; not climbing rapidly)
- Analysis duration (passthrough Stage 1): **~0.1 ms**
- Analyzer errors: **0**
- Rebind / clutch: user confirmed good (re-enter + output starts disabled)
- Thermal observations: user reported run looked good (no blocking heat/throttle noted)
- App memory sample while running: ~107 MB PSS / ~203 MB RSS
- Result: **PASS** — Stage 1 exit gate met

#### Follow-up

- Stage 1 closed. Next work is **Stage 2 — MediaPipe Hand Landmarker benchmark**.
- Session notes for Stage 2 live in [`stage-2-mediapipe-log.md`](./stage-2-mediapipe-log.md).

### Entry template

```markdown
### YYYY-MM-DD — Stage N: short title

- Goal:
- Prompt used:
- Files changed:
- Build/test commands:
- Hardware:
- Measurements:
- Result: PASS / FAIL / PARTIAL
- Problems:
- Decision or follow-up:
```

