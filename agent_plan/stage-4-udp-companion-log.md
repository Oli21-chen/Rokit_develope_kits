# Stage 4 — LAN UDP Mouse Companion

Execution log for Stage 4 of [`hand-pose-mouse-control.md`](./hand-pose-mouse-control.md).

Prior stages:

- Stage 2 PASS — [`stage-2-mediapipe-log.md`](./stage-2-mediapipe-log.md)
- Stage 3 local control — [`stage-3-local-control-log.md`](./stage-3-local-control-log.md)

## Goal

Send Stage 3 `PointerCommand` samples from the glasses to a Windows laptop over **same-LAN UDP**, and inject **relative** mouse motion + left click. Motion can drop packets; button state must fail safe.

## Prerequisite

- Stage 3 crosshair + pinch work on RG-glasses (clutch, LOST freeze/release).
- Glasses and laptop on the **same Wi‑Fi** (private LAN; do not expose the port to the public internet).
- Demo posture unchanged: mid-air control zone; TouchPad clutch off ⇒ no move/click packets.

## Locked decisions

| Topic | Choice |
|-------|--------|
| Transport | UDP unicast, same Wi‑Fi LAN |
| Companion | Python 3 + `pynput==1.8.1` |
| Packet | Little-endian binary v1 (29 bytes) |
| Auth | HMAC-SHA256 truncated to 8 bytes |
| Config | `MouseLinkConfig` + ADB extras `link_host` / `link_port` / `link_token` |
| Defaults | port **9460**, token **`dev-token`** |
| Agent | Starts disarmed; **Arm button** / F8/F9 arm; **F10** / **Esc** disarm+release |
| Heartbeat | 50 ms while clutch on; RX timeout **200 ms** releases buttons |
| Sensitivity | Laptop gain slider; maps glasses **480×640** → screen × gain |
| Reconnect | ReceiveGuard resets on session change / clock rewind (no agent restart) |
| Launcher | `start_mouse_agent.bat` + optional tkinter slider GUI |

## Packet v1

Body 21 + tag 8 = **29** bytes. Magic `0x524B4D31` (`RKM1`).

## Implementation checklist

- [x] `link/` config, packet, client + unit tests
- [x] `INTERNET` + ViewModel heartbeat/send + HUD LINKED
- [x] Intent extras for host/port/token; hub label Stage 4
- [x] `companion/mouse_agent` + README + pinned requirements
- [x] `start_mouse_agent.bat` one-click launcher
- [x] Sensitivity GUI + 480×640 → screen scaling + gain persistence
- [x] Reconnect-safe ReceiveGuard (session / clock rewind / idle reset)
- [ ] Same-LAN smoke test (formal 30-minute log)
- [ ] 30-minute fail-safe checklist + latency notes

## Build / run commands

```powershell
# Glasses APK
cd C:\Users\Olive\Desktop\Rokid_development\GlassesBareDevSample\GlassesBareDevSample
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME = "C:\Users\Olive\AppData\Local\Android\Sdk"
$env:Path = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:Path"
.\gradlew.bat :app:testDebugUnitTest :app:installDebug --console=plain

# Laptop agent
cd C:\Users\Olive\Desktop\Rokid_development\GlassesBareDevSample\companion\mouse_agent
python -m venv .venv
.\.venv\Scripts\activate
pip install -r requirements.txt
# Daily: double-click start_mouse_agent.bat
# Or: python mouse_agent.py --port 9460 --token "dev-token" --gui

# Launch with laptop LAN IP
adb shell am start -n com.rokid.glassesbaredevsample/.activities.main.MainActivity `
  --es link_host "192.168.x.x" --ei link_port 9460 --es link_token "dev-token"

adb logcat -s HandMouseAnalysis MouseLink BareCameraBind
```

## Hardware checklist

- [ ] Laptop agent listening; **Arm** in slider (or F8); Esc/F10 disarms and releases.
- [ ] Glasses Stage 4 scene shows configured host:port.
- [ ] Clutch off → laptop cursor idle.
- [ ] Clutch on → relative move; pinch → left down/up; no stuck button.
- [ ] Kill agent / unplug Wi‑Fi briefly → buttons release within ~200 ms after timeout.
- [ ] Leave scene while pinched → release packet / safe state.
- [ ] 30-minute run; note p95 latency and any false clicks.

Measured results:

- Same-LAN UDP confirmed:
- p95 glass→cursor latency:
- Stuck buttons:
- Result: PENDING HARDWARE

## Development session log

### 2026-07-26 — Stage 4: UDP link + Python agent implemented

- Goal: Implement Stage 4 per this log’s Cursor prompt.
- Files:
  - `link/MouseLinkConfig.kt`, `MouseLinkPacket.kt`, `MouseLinkClient.kt`, `UdpMouseLinkClient.kt`
  - `app/src/test/.../link/MouseLinkPacketTest.kt`
  - `activities/handmouse/HandMouseViewModel.kt`, `HandMouseScreen.kt`
  - `activities/main/MainActivity.kt` (`singleTop` + Intent extras)
  - `activities/main/HubScreen.kt` — Stage 4 label
  - `AndroidManifest.xml` — `INTERNET`
  - `companion/mouse_agent/` — `mouse_agent.py`, `requirements.txt`, `README.md`
- Behavior:
  - encode `PointerCommand` → UDP v1 + HMAC; 50 ms heartbeat while clutch on;
  - release on clutch off / scene exit / camera loss; no landmark streaming;
  - agent disarmed until Arm/F8/F9; 200 ms RX timeout releases left button.
- Build/test: **PASS** (`:app:testDebugUnitTest` + installDebug on RG-glasses).
- Hardware: **NOT RUN** (needs laptop agent + same LAN).
- Result: **PARTIAL** — code ready; exit gate pending hardware.

### 2026-07-27 — Agent reconnect + sensitivity GUI

- **Problem:** After glasses disconnect/reboot, agent kept stale `ReceiveGuard` timestamp → silently rejected all packets (`t_ms` reset on glasses, agent still held old `last_t_ms`).
- **Fix:** Reset guard on session ID change, clock rewind (>2 s), and 30 s idle.
- **Launcher:** `start_mouse_agent.bat` — one-click start with tkinter slider.
- **Sensitivity:** Maps glasses **480×640** plane → primary monitor × **gain** (slider 0.01–2.0); persists to `.mouse_agent_settings.json`.
- **Arm UX:** **Arm / Disarm** buttons in GUI; F9 blocked when cmd.exe focused → use button or F8.
- **Docs:** `test_usage.md`, `companion/mouse_agent/README.md` updated.
- **Tests:** `test_mouse_agent.py` — ReceiveGuard reconnect + `scale_motion`.
- **Hardware:** Same-LAN smoke confirmed (glasses `192.168.0.102` → laptop agent); formal 30-minute log still pending.

## Status

**IN USE ON LAN** — start `start_mouse_agent.bat`, click **Arm**, enable glasses clutch. See [`../test_usage.md`](../test_usage.md).
