# Stage 5a — Persist Mouse Link Config

Execution log for Stage 5a of [`hand-pose-mouse-control.md`](./hand-pose-mouse-control.md).

## Goal

Remove daily `adb link_host` by saving laptop UDP endpoint on the glasses. Intent extras remain for dev override.

## Behavior

| Source | Priority |
|--------|----------|
| Intent extras (`link_host` / `link_port` / `link_token`) | Overrides saved when present |
| `MouseLinkStore` (SharedPreferences) | Used on normal launcher start |
| Empty | HUD prompts adb or long-press save |

**Auto-save:** launching with non-blank `link_host` extra persists host/port/token.

**Manual save:** long-press in Stage 4 → `persistLinkConfig()`.

## Files

- `link/MouseLinkStore.kt` — persist host/port/token
- `link/MouseLinkConfig.kt` — `mergeSavedWithIntent`, `shouldPersistFromIntent`
- `activities/main/MainActivity.kt` — load/save on create / new intent
- `activities/handmouse/HandMouseViewModel.kt` — `persistLinkConfig`, `onLinkConfigPersisted`
- `activities/handmouse/HandMouseScreen.kt` — long-press “保存链路”

## One-time pairing (then daily use without adb)

```powershell
adb shell am start -n com.rokid.glassesbaredevsample/.activities.main.MainActivity `
  --es link_host "192.168.0.100" --ei link_port 9460 --es link_token "dev-token"
```

HUD shows `配置: 链路已保存`. Next launches from glasses launcher reuse saved host.

## Daily use (no adb)

1. Wi‑Fi on (Rokid app).
2. Laptop: double-click **`companion/mouse_agent/start_mouse_agent.bat`** → click **Arm** in slider window.
3. Open app on glasses → Stage 4 → TouchPad click (clutch on).

Full guide: [`../test_usage.md`](../test_usage.md).

## Next (Stage 5b)

LAN discovery beacon from `mouse_agent.py` when laptop IP changes (DHCP).

## Status

**INSTALLED** — pending hardware confirm that launcher start loads saved host.
