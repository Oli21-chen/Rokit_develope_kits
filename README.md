# Rokid glasses development kits

Workspace for building and testing **bare-metal apps on Rokid AI glasses** (RG-glasses / 480×640 Micro-LED), plus Windows laptop agents that turn glasses motion into a real mouse.

This repo is meant to be taken over by another developer. Start here, then follow the linked docs only when you need depth.

## What you get

Two **paired** stacks. Do not mix an app with the other stack’s Python agent.

| Stack | Glasses app | Laptop agent | Default control | Status |
|-------|-------------|--------------|-----------------|--------|
| **A — Hybrid (Solution C)** | [`GlassesBareDevSample/`](GlassesBareDevSample/) | [`companion/mouse_agent/`](companion/mouse_agent/) | On-device MediaPipe + IMU blend (80% head / 20% hand). UDP control on **9460**. | Daily driver in the sample hub (“混合鼠标 · 方案C”) |
| **B — Laptop inference (Plan D)** | [`mouse_controller/`](mouse_controller/) | [`companion_mc/mouse_agent/`](companion_mc/mouse_agent/) | Glasses stream JPEG; MediaPipe runs on the PC at ~30 Hz. Control **9460**, frames **9461**. | Experimental, lower glasses CPU / heat |

Both glasses apps share the same package *namespace* (`com.rokid.glassesbaredevsample`) but **different application IDs**, so they can be installed side by side:

- Sample hub: `com.rokid.glassesbaredevsample`
- Mouse-only Plan D: `com.rokid.mousecontrol.glasses`

Hardware required for camera, IMU, TouchPad, and wear/fold. An emulator is only useful for Compose layout.

## Repo map

```text
.
├── GlassesBareDevSample/   # Android Studio project — hub + demos + hybrid mouse
├── mouse_controller/       # Android Studio project — Plan D mouse only
├── companion/              # Windows UDP mouse agent (stack A)
├── companion_mc/           # Windows UDP + JPEG + MediaPipe agent (stack B)
├── docs/                   # How to develop on glasses
├── agent_plan/             # Stage logs for on-device mouse (Stages 1–6)
├── agent_plan_s2/          # Research notes for laptop-side inference
├── test_usage.md           # Daily hand-mouse runbook (pairing, HUD, troubleshoot)
└── README.md               # You are here
```

Open the **inner** Gradle folder in Android Studio (`settings.gradle.kts` at that level), not this repo root.

## Prerequisites

- Windows laptop (agents use `pynput` + a small Tk slider)
- Android Studio that supports **AGP 9.2.1** / **Kotlin 2.2.10**
- JDK 17 (Studio bundled JBR is fine)
- Android SDK **API 36**, `minSdk` 31
- Python 3.11+ on PATH
- USB drivers + `adb` so `adb devices` shows the glasses as `device`
- Rokid glasses and laptop on the **same Wi‑Fi** (USB is only for install / first pairing)

Default LAN link: port `9460`, token `dev-token`. Bind on a private LAN only. Do not port-forward.

## First-time setup

### 1. Clone

```powershell
git clone https://github.com/Oli21-chen/Rokit_develope_kits.git
cd Rokit_develope_kits
```

### 2. Build and install the glasses app

**Stack A (recommended to learn the hardware):**

```powershell
cd GlassesBareDevSample
.\gradlew.bat :app:testDebugUnitTest :app:installDebug --console=plain
adb shell am start -n com.rokid.glassesbaredevsample/.activities.main.MainActivity
```

**Stack B (Plan D mouse):**

```powershell
cd mouse_controller
.\gradlew.bat :app:testDebugUnitTest :app:installDebug --console=plain
adb shell am start -n com.rokid.mousecontrol.glasses/.activities.main.MainActivity
```

Grant **Camera** / **Microphone** when prompted. Some Rokid flows ask on the phone companion — follow the on-device prompt.

### 3. One-time laptop pairing

The glasses must store the **laptop** IPv4 (not the router `.1`). Find it with `ipconfig`, then:

```powershell
# Stack A
adb shell am start -n com.rokid.glassesbaredevsample/.activities.main.MainActivity `
  --es link_host "192.168.x.x" --ei link_port 9460 --es link_token "dev-token"

# Stack B
adb shell am start -n com.rokid.mousecontrol.glasses/.activities.main.MainActivity `
  --es link_host "192.168.x.x" --ei link_port 9460 --es link_token "dev-token"
```

Re-run this if DHCP gives the laptop a new IP or you clear app data. Daily use after that needs **no USB**.

### 4. Create the Python venv

**Stack A**

```powershell
cd companion\mouse_agent
python -m venv .venv
.\.venv\Scripts\pip install -r requirements.txt
```

**Stack B** (also download the MediaPipe model)

```powershell
cd companion_mc\mouse_agent
python -m venv .venv
.\.venv\Scripts\pip install -r requirements.txt
curl -L -o models\hand_landmarker.task `
  "https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task"
```

## Daily mouse run (no USB)

Both layers must be on or the cursor stays still: laptop **ARMED** and glasses **clutch on**.

1. Connect glasses to the same Wi‑Fi as the laptop (Rokid AI app).
2. Double-click `companion/mouse_agent/start_mouse_agent.bat` (stack A) or `companion_mc/mouse_agent/start_mouse_agent.bat` (stack B).
3. Confirm the console / slider shows **ARMED** (or type `a` + Enter). Type `d` to disarm.
4. Open the matching glasses app from the launcher.
5. Stack A: hub swipe to **混合鼠标 · 方案C** → single click to enter. Stack B opens the mouse scene directly.
6. TouchPad **single click** = clutch on (captures a neutral). **Double click** = back / exit.

| Glasses input | Typical effect |
|---------------|----------------|
| Swipe forward / back | Hub: next / previous item |
| Single click (`ENTER` 66) | Hub: enter · Mouse: clutch on/off |
| Double click (`BACK` 4) | Exit scene / app |
| Long press (`PROG_BLUE` 186) | Save current UDP link |
| Head turn / nod (Solution C) | Coarse cursor |
| Index / hand when visible | Fine cursor (C) or laptop MediaPipe (D) |
| TouchPad click while clutched | Click (C/D; fist is a legacy Stage 6 scheme) |

Full daily runbook, HUD meanings, firewall rule, and reconnect notes: [`test_usage.md`](test_usage.md).

If Windows Firewall blocks the agent:

```powershell
netsh advfirewall firewall add rule name="Rokid Mouse Agent UDP 9460" dir=in action=allow protocol=UDP localport=9460
```

Stack B also needs UDP **9461** inbound for JPEG frames.

## Sample hub (stack A)

| Scene | What it proves |
|-------|----------------|
| 按键与佩戴/折叠 | TouchPad / temple keys, wear & fold |
| 原始音频 | Multi-channel mic capture |
| 拍照 / 录像 | CameraX still + video (no Preview bind) |
| IMU 传感器 | Gyro/accel head pose |
| 混合鼠标 · 方案C | Hybrid pointer → UDP laptop mouse |

Viewport is **480×640**. Keep HUD inside the safe band **y = 80…560**. Background must stay pitch black (`#FF000000`). Density is forced to `1f` — do not use phone Material layouts.

## How the mouse link works

```text
Glasses (clutch ON)
  → MouseLinkPacket v1 (little-endian + truncated HMAC-SHA256)
  → UDP :9460 on the laptop
  → companion mouse_agent (DISARMED until you ARM)
  → pynput relative mouse + left button
```

Stack B adds chunked JPEG (`RKFR`) on **9461**. Saved host/port/token live in `MouseLinkStore` (SharedPreferences). Intent extras `link_host` / `link_port` / `link_token` override and auto-save when `link_host` is non-blank.

Tune laptop **gain** on the slider first (default `0.25`, persisted in `.mouse_agent_settings.json`). Glasses-side `HandMouseConfig.sensitivity` is a second lever.

## Develop and take over

### Code map (stack A)

| Area | Path |
|------|------|
| Hub + navigation | `activities/main/`, `navigation/BareSceneRoutes.kt` |
| Input (keys + broadcasts) | `input/BareGlassesInputDispatcher.kt` |
| Camera bind (no Preview) | `camera/CameraBind.kt` |
| Hand / pointer | `hand/` (`HandMouseConfig`, `PointingController`, `PointerMapper`) |
| IMU / hybrid blend | `sensor/` (`HeadOrientationTracker`, `HybridPointerBlender`) |
| UDP link | `link/` (`MouseLinkPacket`, `UdpMouseLinkClient`, `MouseLinkStore`) |
| Glasses chrome | `ui/design/BareDesign.kt`, `app/CONSTANT.kt` |

Adding a hub scene: route → `HubScreen` entry → `*Screen` (+ ViewModel) → `NavHost` in `MainActivity` → `BareScreenLayout` + `RegisterBareKeyHandler`. Abort system AI / settings broadcasts when the app owns those gestures.

### Tests

```powershell
# Android (from the Gradle project you changed)
.\gradlew.bat :app:testDebugUnitTest

# Python agents
cd companion\mouse_agent
.\.venv\Scripts\python.exe -m unittest test_mouse_agent.py -v

cd companion_mc\mouse_agent
.\.venv\Scripts\python.exe -m unittest test_mouse_agent.py -v
```

### Logcat

```powershell
adb logcat -s HandMouseAnalysis MouseLink BareCameraBind BareGlassesInput
```

### Cursor / agent bootstrap

Paste the session prompt in [`docs/rokid-glasses-dev-guide.md`](docs/rokid-glasses-dev-guide.md) §7.1. Put durable design notes in `agent_plan/` (or `agent_plan_s2/` for laptop inference) and keep this README accurate when the default stack or ports change.

## Document index

| Doc | Use when |
|-----|----------|
| [`test_usage.md`](test_usage.md) | Pairing, daily mouse, HUD, firewall, reconnect |
| [`docs/rokid-glasses-dev-guide.md`](docs/rokid-glasses-dev-guide.md) | Studio setup, hardware checklist, how to add a scene |
| [`companion/mouse_agent/README.md`](companion/mouse_agent/README.md) | Stack A agent CLI and safety |
| [`companion_mc/mouse_agent/README.md`](companion_mc/mouse_agent/README.md) | Stack B agent, ports, model download |
| [`agent_plan/`](agent_plan/) | Stage 1–6 implementation logs |
| [`agent_plan/stage-6-hand-posture-guide.md`](agent_plan/stage-6-hand-posture-guide.md) | Postures + TouchPad key codes |
| [`agent_plan_s2/`](agent_plan_s2/) | Why Plan D exists; open research items |

Known gaps (do not treat as done): LAN discovery when the laptop IP changes (Stage 5b); 60 Hz interpolated UDP (research plan Phase 2); HUD simplified to OFF/MOVE/CLICK.

## Safety and conventions

- Agents start **DISARMED**. Disarm or a 200 ms RX timeout releases the left button.
- `local.properties`, `.venv/`, and Gradle `build/` stay out of git. Recreate the venv on a new machine.
- Default token `dev-token` is for a lab LAN, not production.
- Chinese hub labels are intentional — keep the same tone on new scenes.
}