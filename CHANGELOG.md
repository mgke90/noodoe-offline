# Changelog

## 0.6.0 — 2026-11-07

First stable release.

### Dashboard Studio

- Added the official in-flight transfer engine for large creation bundles.
  Up to ten files stay active, start/finish replies are handled
  asynchronously, and free slots are refilled as each finish is confirmed.
- Added the official `TRANSFER_UPDATE_CONTINUE` keepalive and
  `TRANSFER_UPDATE_DONE` commit.
- The in-flight engine keeps the 9-file clock and 12-file digital
  speedometer within the dashboard's transfer task window.
- Digital speedometer digits now use the stock firmware's native **64×90**
  glyph masks instead of upscaled low-resolution text.

### App

- Classic Bluetooth discovery, system pairing, and SPP connection.
- Device-info parsing: firmware, protocol, hardware version, MAC.
- Optional clock sync on connect; manual sync and custom clock.
- Dashboard preferences: 12/24-hour, km/miles, °C/°F, brightness, language.
- Riding status: speed, ODO, max speed, key-on.
- App, call, and SMS notifications to the dashboard.
- Optional foreground keepalive service.
- Safe log sharing: current log, all logs as ZIP, and safe clear.
- No account, cloud, OTA, social, or server dependency.

### Fixes

- Correct `RIDING_STATUS` offsets (key byte and ODO in km).
- Two-byte little-endian length prefix for notification strings.
- ACK packet-index handling and stale-session recovery.
- Creation slot clearing and transfer-window handling.
- Custom clock preserved across restarts when auto-sync is off.

## Earlier releases

- **0.5.8** — pipelined creation transfer within the dashboard's ten-second
  task window; resume partial files; match replies by transfer id.
- **0.5.7** — handle `ERROR_EXIST` on begin; two-pass creation clear.
- **0.5.6** — upload images before the cfg, matching the official app.
- **0.5.5** — seven distinct weekday images; 5 s keepalive.
- **0.5.0** — integrated the official SR1.5 Pack B assets and the analog/digital
  speedometer editor.
- **0.4.0** — Dashboard Studio clock builder and stock clock test.
- **0.3.0** — app-scoped controller, notifications, and background keepalive.
- **0.2.0** — dashboard preferences and header inset fixes.
- **0.1.0** — first pairing, handshake, clock, and status build.
