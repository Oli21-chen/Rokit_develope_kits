# Test Usage — Hand Mouse (Stage 6 + 5a)

Guide for controlling a Windows laptop mouse from Rokid glasses over **same-LAN UDP**.

**On-device (no internet):** MediaPipe hand detection runs locally from `assets/ml/hand_landmarker.task`.

**LAN required (not public internet):** UDP mouse packets to the laptop.

**Defaults:** port `9460`, token `dev-token`, hub scene **手势鼠标 · Stage 6** (pointing + fist).

---

## Latest usage (after one-time pairing)

Daily flow — **no USB, no adb**:

| Step | Where | Action |
|------|--------|--------|
| 1 | Rokid AI app / glasses | Connect glasses to **same Wi‑Fi** as laptop |
| 2 | Laptop | Double-click **`companion/mouse_agent/start_mouse_agent.bat`** (auto-arms) |
| 3 | Laptop | Confirm cmd shows **`ARMED`** (or type **`a`** + Enter) |
| 4 | Glasses | Open **GlassesBareDevSample** from launcher |
| 5 | Glasses | Hub → swipe to **手势鼠标 · Stage 6** → **single click** enter |
| 6 | Glasses | TouchPad **click** → clutch **on** (P1 neutral captured) |
| 7 | Glasses | **Thumb out/in** = up/down · **wrist left/right** = horizontal · **fist** = click |

Saved link (e.g. `192.168.0.100:9460`) loads automatically from `MouseLinkStore`.

### Easiest laptop start

Double-click:

`C:\Users\Olive\Desktop\Rokid_development\GlassesBareDevSample\companion\mouse_agent\start_mouse_agent.bat`

Opens:

1. **Console** — logs, packet status; type **`a`** + Enter to arm, **`d`** to disarm  
2. **Slider window** — sensitivity + **ARM / Disarm** (may open behind other windows — check taskbar)

Bat starts with **`--auto-arm`**. If cursor idle, verify **ARMED** in cmd or slider.

**Do not rely on F8** if another app registered it. Use **ARM** button or **`a`** + Enter in cmd.

### Manual start (PowerShell)

```powershell
cd C:\Users\Olive\Desktop\Rokid_development\GlassesBareDevSample\companion\mouse_agent
.\.venv\Scripts\activate
python mouse_agent.py --port 9460 --token "dev-token" --gui
```

Console-only (no slider):

```powershell
python mouse_agent.py --no-gui --port 9460 --token "dev-token"
```

---

## Two “enable” steps (both required)

| Layer | Where | Action | HUD / UI |
|-------|--------|--------|----------|
| **Agent arm** | Laptop | Bat auto-arm, **ARM** button, or type **`a`** + Enter in cmd | Status: **ARMED** |
| **Glasses clutch** | Glasses | TouchPad **single click** | `输出: 已启用（UDP+本地）` |

Agent armed alone → packets received but cursor idle if clutch off.  
Clutch on alone → packets sent but cursor idle if agent disarmed.

---

## Sensitivity (laptop slider)

The agent maps glasses motion to your screen using the **480×640** glasses control plane:

```text
laptop_dx = glasses_dx × (screen_width / 480) × gain
laptop_dy = glasses_dy × (screen_height / 640) × gain
```

| Control | Range | Default |
|---------|-------|---------|
| **Gain slider** | 0.01 – 2.0 | 0.25 |
| **Screen scale** | auto (primary monitor) | on |

- **Lower gain** → slower cursor  
- **Higher gain** → faster cursor  
- Last gain saved to `companion/mouse_agent/.mouse_agent_settings.json`  

CLI override: `python mouse_agent.py --gain 0.15`  
Disable screen scaling: `--no-screen-scale`

Glasses-side `HandMouseConfig.sensitivity` still affects packet size; tune the **laptop slider first**.

---

## Laptop controls

| Input | Action |
|-------|--------|
| **ARM** button (slider) | Arm agent |
| Type **`a`** + Enter (cmd window) | Arm (**recommended if F8 fails**) |
| Type **`d`** + Enter | Disarm |
| **Disarm** button | Disarm + release left button |
| **F8 / F9** | May arm if not taken by another app |
| **F10 / Esc** | Disarm |
| **Slider** | Live sensitivity (gain) |
| Close slider window | Quit agent (saves gain) |

---

## Hub & TouchPad key codes (GlassesBareDevSample)

On **RG-glasses**, swipes arrive as **DPAD KeyEvents** (`TouchPadSwipeDetector`). Broadcasts are fallback.

| Gesture | `KeyEvent` | Code | Hub | 手势鼠标 scene |
|---------|------------|------|-----|----------------|
| Single click | `KEYCODE_ENTER` | 66 | Enter scene | Toggle clutch |
| Double click | `KEYCODE_BACK` | 4 | Exit app | Back to hub |
| Long press | `KEYCODE_PROG_BLUE` | 186 | — | Save link |
| Swipe forward | `KEYCODE_DPAD_RIGHT` (+ optional `DPAD_DOWN`) | 22 (+20) | Next item | — |
| Swipe back | `KEYCODE_DPAD_LEFT` (+ optional `DPAD_UP`) | 21 (+19) | Prev item | — |

Broadcast fallback: `ACTION_TWO_FINGER_SWIPE_FORWARD/BACK`, `ACTION_AI_START`, `ACTION_SPRITE_BUTTON_CLICK`.  
Full table: [`agent_plan/stage-6-hand-posture-guide.md`](agent_plan/stage-6-hand-posture-guide.md) §8.

**Hub navigation:** swipe = cycle item · single click = enter · double click = exit app.

---

## One-time pairing (first run or new laptop IP)

Run **once** over USB to save laptop IP on the glasses. Uses Intent extras → auto-saved by `MouseLinkConfig.shouldPersistFromIntent`.

```powershell
$env:Path += ";C:\Users\Olive\AppData\Local\Android\Sdk\platform-tools"

adb devices
adb shell am start -n com.rokid.glassesbaredevsample/.activities.main.MainActivity `
  --es link_host "192.168.0.100" --ei link_port 9460 --es link_token "dev-token"
```

Replace `192.168.0.100` with your laptop IP:

```powershell
ipconfig | findstr /i "IPv4"
```

**Confirm save:** Stage 4 HUD shows `配置: 链路已保存`.

**Alternative save:** enter Stage 4 with a valid link, then TouchPad **long press** → `配置: 链路已保存`.

---

## When you need adb again

| Situation | Action |
|-----------|--------|
| First install / never paired | One-time pairing command above |
| Laptop got a new DHCP IP | Re-run pairing with new IP |
| Cleared app data | Re-run pairing |
| Normal daily use | **No adb** — open app from glasses |

---

## Link config (Stage 5a)

**Load order** (`MouseLinkConfig.mergeSavedWithIntent`):

1. Read saved `host` / `port` / `token` from `MouseLinkStore` (SharedPreferences)
2. If launch Intent has `link_host` / `link_port` / `link_token` → **override** saved values
3. If Intent includes non-blank `link_host` → **auto-save** to device

| Intent extra | Type | Default |
|--------------|------|---------|
| `link_host` | String | (saved or empty) |
| `link_port` | Int | `9460` |
| `link_token` | String | `dev-token` |

**Do not use** router IP (`192.168.0.1`) as `link_host` — use the **laptop** IP.

**Example LAN (DHCP may change after reboot):**

| Device | Wi‑Fi | IP |
|--------|-------|-----|
| Laptop | `TP-Link_51EA` | `192.168.0.100` |
| Glasses | `TP-Link_51EA` | `192.168.0.102` (was `.105`) |
| Router (wrong for `link_host`) | — | `192.168.0.1` |

---

## Glasses controls (Stage 6 — dual-axis + fist)

| Input | Action |
|-------|--------|
| **Single click** (`ENTER` 66) | Toggle clutch; **captures P1 neutral** when enabling |
| **Double click** (`BACK` 4) | Back to hub; release buttons |
| **Long press** (`PROG_BLUE` 186) | Save current link to device |
| **Four fingers + wrist rotate** | Cursor **left / right** (and diagonals) |
| **Thumb out** (vs neutral) | Cursor **up** |
| **Thumb toward palm** (fingers open) | Cursor **down** |
| **Neutral P1** | Cursor idle |
| **Full fist** | Left mouse button |

Legacy palm-move + pinch: `HandMouseConfig.controlScheme = TRANSLATION_PINCH`.

Full control cases: [`agent_plan/stage-6-pointing-fist-control-case.md`](agent_plan/stage-6-pointing-fist-control-case.md).

**Posture diagrams (P0–P8 + key codes):** [`agent_plan/stage-6-hand-posture-guide.md`](agent_plan/stage-6-hand-posture-guide.md)

### Demo posture

1. Hand in mid-air; **four fingers extended**, thumb neutral.
2. Clutch on → hold **P1 neutral** ~1 s.
3. **Thumb out/in** for up/down; **rotate wrist** for left/right; combine for diagonals.
4. **Full fist** to click; open hand to release.

Clutch **off** → no UDP move/click packets, even if agent is armed.

---

## HUD checklist (when debugging)

| HUD line | Good value |
|----------|------------|
| `链路` | **已发送** `host:port seq=N` (not 发送失败) |
| `输出` | **已启用（UDP+本地）** |
| `手` | **有** |
| `手势` | **TRACKING** (not IDLE / LOST) |
| `指针` | non-zero **dx / dy** when moving hand |

| Slider window | Good value |
|---------------|------------|
| Status | **ARMED** |
| Glasses | `192.168.0.x:port` |
| Packets | `accept` climbing |

---

## HUD link status

| Display | Meaning |
|---------|---------|
| `已配置 host:port` | Host loaded; no packets yet |
| `已发送 host:port seq=N` | UDP sending OK |
| `发送失败 host:port seq=N` | Send error — see `链路错误` |
| `未配置 host（长按保存 / adb）` | No saved host — pair once |
| `配置: 链路已保存` | Save succeeded |

---

## Reconnect (glasses Wi‑Fi off/on or reboot)

**No agent restart needed.** The agent resets its receiver when:

- Glasses **session ID** changes (app restart)  
- Glasses **clock rewinds** (`elapsedRealtime` reset after reboot)  
- **30 s** idle with no packets  

Console shows `ReceiveGuard reset (...)` and `glasses linked peer=... session=...`.

After reconnect:

1. Agent still running → click **Arm** again if you disarmed  
2. Glasses → Stage 4 → clutch **on**  
3. Confirm slider shows linked peer + rising `accept` count  

---

## Network check

Wi‑Fi connects glasses to LAN; **USB does not replace Wi‑Fi** for mouse control.

```powershell
adb shell ip addr show wlan0 | findstr "inet "
ipconfig | findstr "IPv4"
netstat -an | findstr "9460"
ping -n 2 192.168.0.102
```

| Check | Good |
|-------|------|
| Glasses `wlan0` | `inet 192.168.0.x` |
| Laptop IP | Same subnet as glasses |
| Agent | `UDP 0.0.0.0:9460` listening |

Connect Wi‑Fi via **Rokid AI app** or glasses settings if `wlan0` has no IP.

---

## Developer setup

### ADB on PATH

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME = "C:\Users\Olive\AppData\Local\Android\Sdk"
$env:Path = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:Path"
adb devices
```

Permanent: add `C:\Users\Olive\AppData\Local\Android\Sdk\platform-tools` to user **Path**.

### Build / install (after code changes)

```powershell
cd C:\Users\Olive\Desktop\Rokid_development\GlassesBareDevSample\GlassesBareDevSample
.\gradlew.bat :app:testDebugUnitTest :app:installDebug --console=plain
```

### First-time mouse agent setup

```powershell
cd C:\Users\Olive\Desktop\Rokid_development\GlassesBareDevSample\companion\mouse_agent
python -m venv .venv
.\.venv\Scripts\pip install -r requirements.txt
```

Then use `start_mouse_agent.bat` for daily runs.

### Logcat

```powershell
adb logcat -s HandMouseAnalysis MouseLink BareCameraBind
```

Useful patterns:

```powershell
adb logcat -d -s HandMouseAnalysis | findstr "TRACKING IDLE linkSeq"
adb logcat -d -s MouseLink
```

---

## Troubleshooting

### `ENETUNREACH (Network is unreachable)`

1. Glasses not on Wi‑Fi → connect via Rokid app (same SSID as laptop).
2. Wrong host → use laptop IP, not router `.1`.
3. Re-pair if IP changed (one-time adb command above).

### Wi‑Fi OK but mouse idle

1. Agent running? Slider **ARMED**?
2. Glasses clutch **on** (`输出: 已启用`)?
3. Hand in camera (`手: 有`, `手势: TRACKING`)?
4. Firewall (Admin PowerShell):

```powershell
netsh advfirewall firewall add rule name="Rokid Mouse Agent UDP 9460" dir=in action=allow protocol=UDP localport=9460
```

### F8 / F9 do nothing

Another app may own F8. Use **ARM** in slider, **`a`** + Enter in cmd, or restart bat (**`--auto-arm`**).

### GUI “未响应” / freezes when moving hand

Fixed in `mouse_agent.py` (deadlock: mouse I/O no longer holds agent lock). Restart bat after updating.

### After glasses reconnect, agent ignores packets

Fixed in agent — update `mouse_agent.py` and restart bat once. Look for `ReceiveGuard reset` / `glasses linked` in console.

### Cursor too slow / fast

1. Adjust **gain slider** on laptop (saved automatically).  
2. If still wrong, edit `hand/HandMouseConfig.kt` → rebuild (glasses-side `sensitivity`, default `2000f`).

---

## Hardware checklist

- [ ] One-time pairing done; launcher start shows saved `host:port`
- [ ] `start_mouse_agent.bat` → **ARMED** (auto or `a` + Enter)
- [ ] Hub swipe/click works (DPAD 21/22, ENTER 66, BACK 4)
- [ ] Wi‑Fi connected; link line `已发送` when clutch on
- [ ] Clutch off → idle; clutch on → thumb/finger move + fist click
- [ ] Glasses reconnect without restarting agent (session reset works)
- [ ] Gain slider tuned; settings persist in `.mouse_agent_settings.json`

---

## Quick reference

| Item | Value |
|------|-------|
| App ID | `com.rokid.glassesbaredevsample` |
| Activity | `.activities.main.MainActivity` |
| UDP port / token | `9460` / `dev-token` |
| Persist store | `MouseLinkStore` → `mouse_link_config` prefs |
| Laptop launcher | `companion/mouse_agent/start_mouse_agent.bat` |
| Sensitivity settings | `companion/mouse_agent/.mouse_agent_settings.json` |
| Glasses ref plane | 480 × 640 px |
| Laptop IP (example) | `192.168.0.100` |

---

## Related docs

| Document | Purpose |
|----------|---------|
| `agent_plan/stage-5-link-persist-log.md` | Stage 5a persist design |
| `agent_plan/stage-4-udp-companion-log.md` | UDP packet + agent updates |
| `companion/mouse_agent/README.md` | Python agent details |
| `docs/rokid-glasses-dev-guide.md` | General dev guide |

**Coming (Stage 5b):** LAN discovery from `mouse_agent.py` when laptop IP changes — no re-pairing.
