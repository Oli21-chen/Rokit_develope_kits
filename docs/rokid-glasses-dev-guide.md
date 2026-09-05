# Rokid AI Glasses Development Guide

Living guide for building apps on Rokid AI glasses using this workspace, Android Studio, and Cursor agents.

Update this file when workflows, device quirks, or project conventions change. Feature-specific plans live under `../agent_plan/`.

---

## 1. Workspace layout

```
GlassesBareDevSample/
├── GlassesBareDevSample/     # Android Studio project (open this folder)
│   ├── app/                  # Main application module
│   ├── gradle/
│   └── ...
├── agent_plan/               # Feature plans (e.g. hand-pose mouse)
└── docs/                     # This guide and other stable docs
```

| Path | Role |
|------|------|
| `GlassesBareDevSample/` | Gradle app: Compose UI, camera, IMU, keys |
| `agent_plan/` | Design notes before / during implementation |
| `docs/` | How to develop, prompt, build, and test on hardware |

**Application ID:** `com.rokid.glassesbaredevsample`  
**Package root:** `com.rokid.glassesbaredevsample`  
**Min SDK / Target SDK:** 31 / 36  
**UI:** Jetpack Compose, fixed glasses viewport **480×640** px

---

## 2. What this sample demonstrates

Hub menu (“裸机能力”) → feature scenes:

| Scene | Purpose | Key packages |
|-------|---------|--------------|
| Keys & wear/fold | TouchPad / temple keys, wear & fold broadcasts | `input/`, `activities/keys/` |
| Raw audio | Multi-channel mic capture | `activities/audio/` |
| Photo | CameraX still capture | `camera/`, `activities/photo/` |
| Video | CameraX recording | `activities/video/` |
| IMU | Gyro/accel head pose + axis checks | `sensor/`, `activities/imu/` |
| Hand mouse · Stage 1 | CameraX ImageAnalysis telemetry and local output clutch (PASS on RG-glasses) | `camera/`, `activities/handmouse/` |
| Hand mouse · Stage 2 | MediaPipe Hand Landmarker on-device benchmark (**PASS**) | `hand/`, `camera/`, `activities/handmouse/` — see `agent_plan/stage-2-mediapipe-log.md` |
| Hand mouse · Stage 3 (current) | Local relative pointer + pinch + clutch | `hand/` mapper/classifier, `activities/handmouse/` — see `agent_plan/stage-3-local-control-log.md` |
| Hand mouse · Stage 4 (current) | UDP → Windows mouse agent | `link/`, `companion/mouse_agent/` — see `agent_plan/stage-4-udp-companion-log.md` |

**Daily mouse control (Stage 4 + 5a):** see [`../test_usage.md`](../test_usage.md) — double-click `companion/mouse_agent/start_mouse_agent.bat`, **Arm** in slider, enable glasses clutch.

**Interaction model (glasses-first):**

- Hub: **swipe** (DPAD right/left) → cycle item · **single click** (ENTER) → enter · **double click** (BACK) → exit app
- Hand mouse: **thumb out/in** = vertical · **four-finger wrist rotate** = horizontal · **fist** = click
- TouchPad clutch + laptop agent **ARM** both required for UDP mouse

Key code reference: [`../agent_plan/stage-6-hand-posture-guide.md`](../agent_plan/stage-6-hand-posture-guide.md) §8.

Input is centralized in `BareGlassesInputDispatcher` (KeyEvents + ordered broadcasts). System AI / settings / power actions must be aborted when the app owns those gestures.

---

## 3. Environment setup (Android Studio)

### 3.1 Required tools

1. **Android Studio** (current stable; AGP in this project is 9.x — use a Studio version that supports it).
2. **JDK 17** (or the JDK bundled with Android Studio).
3. **Android SDK** with API **36** platform + build-tools matching the project.
4. **USB drivers** for the glasses / ADB bridge (Rokid / OEM drivers if Windows does not see the device).
5. **Cursor** (optional but recommended) with this workspace root:
   `C:\Users\Olive\Desktop\Rokid_development\GlassesBareDevSample`

### 3.2 Open the project

1. Android Studio → **Open** → select  
   `...\GlassesBareDevSample\GlassesBareDevSample`  
   (the folder that contains `settings.gradle.kts`, not the parent).
2. Let Gradle sync finish. If sync fails, check `local.properties` → `sdk.dir=...`.
3. Wait until the project indexes and the run configuration **app** appears.

### 3.3 Device / glasses prep

1. Enable **Developer options** and **USB debugging** on the glasses (or companion path Rokid documents for your model).
2. Connect USB; confirm ADB:

```powershell
adb devices
```

You should see a device in `device` state (not `unauthorized`). Tap **Allow** on the glasses/companion if prompted.

3. Optional wireless ADB (same LAN):

```powershell
adb tcpip 5555
adb connect <glasses-ip>:5555
adb devices
```

### 3.4 First run from Android Studio

1. Select the physical glasses device in the device dropdown (not an emulator unless you only need UI layout).
2. Run ▶ **app**.
3. Grant **Camera** / **Microphone** when prompted. Some copy in this sample says authorize on the **phone side** if Rokid routes permissions there — follow the on-device prompt.
4. Keep the glasses awake; the sample uses fullscreen + keep-screen-on.

**Emulator note:** Emulators are useful for Compose layout and navigation only. Camera, real IMU axes, temple/TouchPad broadcasts, and wear/fold events require **hardware**.

---

## 4. Build & deploy without Studio UI

From the Android project directory:

```powershell
cd C:\Users\Olive\Desktop\Rokid_development\GlassesBareDevSample\GlassesBareDevSample

.\gradlew.bat :app:installDebug
adb shell am start -n com.rokid.glassesbaredevsample/.activities.main.MainActivity
```

Useful commands:

```powershell
# Logcat filtered to this app
adb logcat --pid=$(adb shell pidof -s com.rokid.glassesbaredevsample)

# Or by tag
adb logcat -s BareCameraBind BareGlassesInput

# Pull media saved by sample demos (paths may vary)
adb pull /sdcard/Pictures/bare_photo .
adb pull /sdcard/Video/bare_video .

# Reinstall clean
adb uninstall com.rokid.glassesbaredevsample
.\gradlew.bat :app:installDebug
```

---

## 5. Hardware test checklist

Use this when validating a change on real glasses.

### 5.1 Baseline smoke test

- [ ] App launches fullscreen, hub title visible in safe area  
- [ ] Single click cycles hub entries; double click enters  
- [ ] Double click (or mapped back) returns from a scene  
- [ ] Screen stays on during demos  
- [ ] No system AI / settings stealing key events while in app  

### 5.2 Per-capability checks

| Capability | How to test |
|------------|-------------|
| Keys / wear | Enter Keys scene; click / double / long; fold arms / wear-remove; confirm log lines |
| Audio | Grant mic; start/stop; pull WAV/PCM from device storage |
| Photo | Grant camera; capture; confirm path on UI; `adb pull` file |
| Video | Start/stop recording; pull file; play on PC |
| IMU | Wear glasses; move head; ball/meters respond; run axis verification if prompted |
| Hand mouse · Stage 1 | Grant camera; confirm 640×480/rotation/FPS counters; toggle local output; run 10 minutes; return and re-enter |
| Hand mouse · Stage 2 | Enter Stage 2; wait for MediaPipe ready; confirm hand present/landmarks; record analyzed FPS + inference avg/p95 at 1/5/10 min; rebind ×5 |

### 5.3 UI / display rules (do not break)

- Content must fit **480×640**; prefer content inside safe band **y = 80…560**.  
- Background should stay **pure black** (`#FF000000`) — on glasses, black ≈ non-emissive.  
- Density is forced to **1f** inside `GlassesDisplayFrame` — do not assume phone density.  
- Keep HUD sparse; avoid phone-style Material dashboards.

### 5.4 When something fails

1. `adb devices` — connected?  
2. Logcat around the feature tag.  
3. Permissions: Settings → Apps → this app.  
4. Camera exclusive use: stop other camera apps / previous bind.  
5. Reboot glasses; reinstall debug APK.  
6. Compare with an untouched hub demo (photo/IMU) to isolate your change.

---

## 6. How to extend the app (code map)

When adding a feature, follow existing patterns.

### 6.1 Typical new scene checklist

1. Add route in `navigation/BareSceneRoutes.kt`.  
2. Add hub entry in `activities/main/HubScreen.kt`.  
3. Create `activities/<feature>/` with `*Screen.kt` (+ ViewModel if stateful).  
4. Register composable in `MainActivity` `NavHost`.  
5. Use `BareScreenLayout` + `RegisterBareKeyHandler` for glasses keys.  
6. Declare permissions in `AndroidManifest.xml` + runtime request helpers.  
7. Keep UI inside `GlassesDisplayFrame` (already wrapped at activity root).

### 6.2 Important existing building blocks

| Building block | File | Use for |
|----------------|------|---------|
| Camera bind (no Preview) | `camera/CameraBind.kt` | Photo, video, future `ImageAnalysis` |
| Key dispatcher | `input/BareGlassesInputDispatcher.kt` | Click / double / long + broadcasts |
| Display constants | `app/CONSTANT.kt` | Resolution, safe area, audio format |
| Layout chrome | `ui/design/BareDesign.kt` | Titles, key guides, pages |
| Media paths | `utils/BareMediaStorage.kt` | Saving captures |
| Head pose | `sensor/HeadOrientationTracker.kt` | IMU fusion |

### 6.3 Camera for ML / analysis

Current sample binds **ImageCapture** / **VideoCapture** only (aligned with CXRSSDKSamples: **no Preview**). For hand pose or similar:

1. Add CameraX `ImageAnalysis` with `STRATEGY_KEEP_ONLY_LATEST`.  
2. Prefer lower resolution (e.g. 640×480) and measure FPS on device.  
3. Bind via `rememberCameraBound(..., useCases = { arrayOf(analysis, ...) })`.  
4. Do heavy ML off the main thread; never block the analyzer callback.

See `../agent_plan/hand-pose-mouse-control.md` for the mouse-control plan.

---

## 7. Developing with Cursor agents (prompts)

Treat agents as pair programmers that must respect glasses constraints. Put durable plans in `agent_plan/`, keep this guide current, and use **Agent mode** for code changes.

### 7.1 Session bootstrap (paste first)

Use when starting a new chat about this project:

```text
You are helping me develop Rokid AI glasses apps in this workspace.

Context:
- Android project root: GlassesBareDevSample/GlassesBareDevSample
- Package: com.rokid.glassesbaredevsample
- UI: Jetpack Compose, fixed 480×640 glasses viewport, pitch-black background, safe area y=80..560
- Input: BareGlassesInputDispatcher (click / double-click / long-press); abort system broadcasts when owning keys
- Camera: CameraX via camera/CameraBind.kt; currently no Preview; back camera
- Docs: docs/rokid-glasses-dev-guide.md
- Feature plans: agent_plan/

Rules:
- Match existing package layout and Bare* UI/input patterns
- Prefer sparse glasses HUD over phone Material layouts
- Do not invent Rokid proprietary SDK APIs unless they already exist in the repo
- After code changes, tell me exactly how to build/install and what to test on hardware
- Ask before large refactors unrelated to the request
```

### 7.2 Explore before coding

```text
Read docs/rokid-glasses-dev-guide.md and explore the Android app structure.
Summarize where I should add <FEATURE> and which existing modules to reuse.
Do not write code yet. List files to touch and risks for glasses hardware.
```

### 7.3 Implement a hub feature

```text
Implement a new hub scene "<NAME>" for Rokid glasses in GlassesBareDevSample.

Requirements:
- <functional requirements>
- Use BareScreenLayout + RegisterBareKeyHandler
- Add BareSceneRoutes entry and HubScreen item
- Wire NavHost in MainActivity
- Keep UI inside 480×640 safe area; black background
- Permissions: <list>

Follow patterns from PhotoScreen / ImuScreen / KeysWearScreen.
When done, give: files changed, install commands, and a hardware test checklist.
```

### 7.4 Camera / MediaPipe / analysis work

```text
Add CameraX ImageAnalysis to this Rokid glasses sample for <PURPOSE>.

Constraints:
- Reuse camera/CameraBind.kt
- No Preview unless clearly required
- STRATEGY_KEEP_ONLY_LATEST, target ~15–20 FPS on device
- Off-main-thread processing
- Log FPS and failures with a clear tag

Also document how I should measure FPS via logcat on hardware.
```

### 7.5 Laptop companion / network control

```text
Design then implement a minimal LAN protocol from the glasses app to a Windows laptop agent for <PURPOSE>.

Constraints:
- Add only needed Android permissions (e.g. INTERNET)
- Prefer UDP or WebSocket on local Wi‑Fi
- Define packet schema and a tiny Python or C# receiver
- Security: bind localhost/LAN only; no public exposure
- Provide run steps for both glasses APK and laptop agent
```

### 7.6 Debug a hardware failure

```text
I'm testing on Rokid glasses hardware. Symptom: <what I see / don't see>.
Steps I tried: <adb / Studio / permissions>.
Relevant logcat: <paste>.

Investigate the codebase for likely causes in input, camera, permissions, or lifecycle.
Propose the smallest fix and a retest script (adb + manual gestures).
```

### 7.7 Keep docs / plans sustainable

```text
Update docs/rokid-glasses-dev-guide.md and/or agent_plan/<plan>.md to reflect what we just implemented:
- new scene routes
- new permissions
- new test steps
- any device quirks we discovered

Keep the guide concise; prefer checklists and copy-paste prompts over long prose.
```

### 7.8 Agent + Android Studio division of labor

| Do in Cursor agent | Do in Android Studio |
|--------------------|----------------------|
| Explore code, implement features, write plans/docs | Gradle sync, SDK manager, device manager |
| Propose Gradle dependency edits | Resolve sync errors, invalid SDK paths |
| Draft logcat filters / adb scripts | Run ▶, debugger breakpoints, Layout Inspector (limited on glasses) |
| Generate companion PC scripts | Sign / release builds if needed |

When the agent edits Gradle files: **Sync Project with Gradle Files** in Android Studio before Run.

### 7.9 Shell / agent commands that need care

Prefer the agent to run (or you run) from the Android project dir:

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:installDebug
adb devices
adb logcat -d -t 200
```

If the agent cannot see the USB device, run `adb devices` yourself in a terminal and paste the result into the chat.

---

## 8. Recommended development loop

1. **Write a short plan** in `agent_plan/<feature>.md` (goals, risks, phases).  
2. **Bootstrap** a Cursor chat with §7.1 + the plan path.  
3. **Explore** (§7.2) → agree on files.  
4. **Implement** small slices; sync Gradle in Android Studio.  
5. **Install on glasses**; run §5 checklist for that slice.  
6. **Paste logcat / symptoms** back to the agent if broken.  
7. **Update this guide** when you learn a durable quirk or new prompt that works well.

Prefer vertical slices (one demo scene that works end-to-end) over large unfinished frameworks.

---

## 9. Conventions & pitfalls

- **Portrait only** — activity is portrait-locked.  
- **No Preview by default** — mirrors vendor samples; analysis/capture still works.  
- **Abort ordered broadcasts** — otherwise temple/TouchPad gestures may open system AI/settings.  
- **Phone-like density** — wrong; viewport forces density 1.  
- **Storage paths** — prefer sample `bare_photo` / `bare_video` dirs for easy `adb pull`.  
- **Do not assume magnetometer** — current head pose is gyro-centric with accel for level UI.  
- **Chinese UI strings** exist in hub/scenes — keep tone consistent when adding labels.

---

## 10. Related documents

| Document | Location |
|----------|----------|
| Hand pose → laptop mouse plan | `../agent_plan/hand-pose-mouse-control.md` |
| Stage 1 session log | `../agent_plan/developing_step_logs.md` |
| Stage 2 MediaPipe log | `../agent_plan/stage-2-mediapipe-log.md` |
| Stage 3 local control log | `../agent_plan/stage-3-local-control-log.md` |
| Stage 4 UDP companion log | `../agent_plan/stage-4-udp-companion-log.md` |
| Decision / revision log | `../agent_plan/hand-pose-mouse-control-log.md` |
| This guide | `./rokid-glasses-dev-guide.md` |

Add new feature plans under `agent_plan/` and link them here when they become active workstreams.

---

## 11. Changelog (maintain this)

| Date | Change |
|------|--------|
| 2026-07-27 | Stage 6 docs: dual-axis posture (thumb vertical, four-finger horizontal), TouchPad key codes (ENTER/BACK/DPAD), hub navigation |
| 2026-07-27 | Mouse agent: `start_mouse_agent.bat`, sensitivity slider, reconnect-safe guard, Arm button; docs in `test_usage.md` |
| 2026-07-26 | Stage 4 UDP MouseLink + Python companion installed; same-LAN hardware checklist open |
| 2026-07-26 | Prepared Stage 4 UDP companion plan log (packet v1, Python agent, fail-safe checklist) |
| 2026-07-26 | Stage 3 local pointer + pinch + clutch installed; demo posture documented in stage-3 log |
| 2026-07-26 | Stage 2 MediaPipe Hand Landmarker **PASS** on RG-glasses (RGBA path, overlay, display axis); Stage 3 next |
| 2026-07-26 | Stage 2 MediaPipe Hand Landmarker (VIDEO/CPU) wired; model asset + HUD metrics; hardware gate open |
| 2026-07-26 | Stage 1 hardware PASS on RG-glasses (480×640 @270°, ~29.7 FPS); Stage 2 MediaPipe next |
| 2026-07-26 | Added Hand Mouse Stage 1 scene and CameraX ImageAnalysis hardware test |
| 2026-07-26 | Initial sustainable guide: Studio setup, hardware test, Cursor prompt pack, extend checklist |

When you update this file, append a row above with the date and a one-line summary.
