# Mouse agent (Stage 4 + laptop inference)

Python UDP receiver for Rokid glasses mouse control. In **Scheme D (default)** the glasses stream JPEG camera frames; this agent runs **MediaPipe Hand Landmarker** on the laptop at **30 Hz** and injects relative mouse motion + left click on Windows.

## Quick start (Windows)

**Double-click** `start_mouse_agent.bat` — opens console + sensitivity slider (control `:9460`, frames `:9461`).

1. Click **ARM** in the slider window (or type `a` + Enter in the console)
2. On glasses: Hand Mouse → TouchPad click (clutch ON) → move hand

One-time setup if `.venv` is missing:

```powershell
cd C:\Users\Olive\Desktop\Rokid_development\GlassesBareDevSample\companion_mc\mouse_agent
python -m venv .venv
.\.venv\Scripts\pip install -r requirements.txt
# Download model (see models/README.md)
Invoke-WebRequest -Uri "https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task" -OutFile "models/hand_landmarker.task"
```

## Ports

| Port | Direction | Protocol |
|------|-----------|----------|
| **9460** | Glasses → laptop | `MouseLinkPacket` control (clutch, click, recenter, pause) |
| **9461** | Glasses → laptop | `RKFR` chunked JPEG frames |

Legacy on-device inference: run with `--no-laptop-inference` (motion dx/dy in control packets only).

## Safety

- Starts **DISARMED** — packets are validated but do not move the mouse until **armed**.
- **Disarm** releases left button; **200 ms** RX timeout also releases buttons.
- Glasses TouchPad **clutch** must be on to send active move packets.
- Bind on a private LAN only; do not port-forward to the public internet.

## Controls

| Input | Action |
|-------|--------|
| **ARM** button | Arm agent |
| Type **`a`** + Enter in cmd | Arm (if hotkeys fail) |
| Type **`d`** + Enter | Disarm |
| **Disarm** button | Disarm + release left button |
| **Slider** | Live gain 0.01 – 2.0 |
| Close GUI window | Quit (saves settings) |

`start_mouse_agent.bat` passes **`--auto-arm`**. See [`../../test_usage.md`](../../test_usage.md) for TouchPad key codes on glasses.

## Sensitivity mapping

Maps glasses **480×640** control plane → primary monitor:

```text
laptop_dx = glasses_dx × (screen_width / 480) × gain
laptop_dy = glasses_dy × (screen_height / 640) × gain
```

- Default **gain**: `0.25` (saved in `.mouse_agent_settings.json`)
- Screen size: auto-detected via Windows API
- Disable screen scale: `--no-screen-scale`

## CLI

```powershell
.\.venv\Scripts\activate
python mouse_agent.py --port 9460 --frame-port 9461 --token "dev-token" --gui --laptop-inference
python mouse_agent.py --no-laptop-inference --no-gui   # legacy glasses-side MediaPipe
python mouse_agent.py --gain 0.15 --gui
python mouse_agent.py --motion-hz 30 --model models/hand_landmarker.task
```

## Reconnect-safe receiver

After glasses reboot or Wi‑Fi reconnect, the agent **does not need a restart**. `ReceiveGuard` resets on:

- New glasses **session ID**
- **Clock rewind** (`elapsedRealtime` reset after reboot)
- **30 s** packet idle

Console: `ReceiveGuard reset (...)` then `glasses linked peer=...`.

## Glasses pairing

Same Wi‑Fi as the laptop. One-time USB pairing:

```powershell
adb shell am start -n com.rokid.glassesbaredevsample/.activities.main.MainActivity `
  --es link_host "192.168.x.x" --ei link_port 9460 --es link_token "dev-token"
```

Hub → **手势鼠标 · Stage 4** → **Arm** on laptop → TouchPad click (clutch) → move / pinch.

See [`../../test_usage.md`](../../test_usage.md) for full daily flow and troubleshooting.

## Tests

```powershell
.\.venv\Scripts\python.exe -m unittest test_mouse_agent.py -v
```

## Packet

See [`../../agent_plan/stage-4-udp-companion-log.md`](../../agent_plan/stage-4-udp-companion-log.md) (little-endian v1 + HMAC-SHA256 truncated tag).

## Files

| File | Purpose |
|------|---------|
| `mouse_agent.py` | UDP control + frame servers, 30 Hz motion loop, GUI |
| `frame_link.py` | RKFR chunked JPEG decode |
| `hand_tracker.py` | MediaPipe Hand Landmarker |
| `pointer_mapper.py` | Touch-gated trackpad delta mapping |
| `start_mouse_agent.bat` | One-click launcher |
| `test_mouse_agent.py` | Unit tests |
| `models/hand_landmarker.task` | MediaPipe model (download separately) |
