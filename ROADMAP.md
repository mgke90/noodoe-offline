# Roadmap — Noodoe Offline

## Goal

A universal offline Android companion for every scooter with a KYMCO Noodoe /
Sunray dashboard. No login, cloud, OTA or social.

## Feature matrix

| Area | Offline feature | Status |
|---|---|---|
| Pairing | Classic discovery, system bonding, auto-select `KYMCO Noodoe`/`Sunray` | Phase 1 (built) |
| Connection | SPP session, handshake, device info, version detection | Phase 1 (built) |
| Clock | Sync on connect, sync now, custom date/time | Phase 1 (built) |
| Status | `RIDING_STATUS`: speed, ODO, max speed, key-on | Phase 1 (built) |
| Logs | Last-log share, all-logs ZIP share, safe clear | Phase 1 (built) |
| Phone status | `MOBILE_STATUS`: dash clock, GPS/cellular/battery icons | Phase 2 (built) |
| Settings | 12/24 h, km/miles, °C/°F, brightness, language (shutdown/owner later) | Phase 2 (built) |
| Status V2 | `RIDING_STATUS_V2`: range, fuel gauge, RPM, charging state | Phase 2 |
| Latest status | `GET_LATEST_STATUS`: SOC, voltage, temperatures, tire pressure, trips | Phase 2 |
| Notifications | SMS, calls, app notifications + foreground keepalive | Phase 2 (built) |
| Advanced settings | `SUPPORT_ADVANCED_SETTINGS`, TCS, blind spot, QS, lift control, charge limits | Phase 2/3 |
| Dashboard | Local creation/theme install via SPP file transfer | Phase 3 (clock + speedometer editors built, 0.6.0) |
| Maintenance | Maintenance plan read/write, diagnostics | Phase 3 |
| Firmware OTA | — | Not planned |
| Login/cloud/social | — | Not planned |

## Universal device handling

- Detect `VERSION_1_0`, `VERSION_1_5`, `VERSION_2_0` from the device-info reply.
- Use `RIDING_STATUS` (cmd 12) as the universal basic status; it is supported
  from protocol 0.0 through 2.x.
- Use `RIDING_STATUS_V2` (25) and `GET_LATEST_STATUS` (34) only when the
  dashboard reports 2.x; hide fields the scooter does not support.
- Use `SUPPORT_ADVANCED_SETTINGS` (33) before writing advanced settings.
- Treat 1.0 devices as limited mode; this APK has no legacy clock path.
- ODO is a u32 in **km** at `RIDING_STATUS` data[3..6]; key-on is data[2].
  The earlier “meters at data[1..4]” reading was a two-byte offset error.

## Phase plan

### Phase 1 — foundation (built)
Pairing, session, clock, device info, basic status, time format, logs.

### Phase 2 — persistent + richer data (current)
- Done: foreground `connectedDevice` service with keepalive/auto-reconnect.
- Done: notification listener service + SMS/call/app notification commands,
  permission/access UI, test notification.
- Next: `RIDING_STATUS_V2` and `GET_LATEST_STATUS` cards (battery, range,
  temps, tire pressure, trips) for 2.0 scooters.
- Done: units, brightness, language, header insets, all-logs ZIP sharing,
  scroll/live-log fixes.
- Later: shutdown time, owner name.

### Phase 3 — dashboard + maintenance (current)
- Done: local clock creation builder and stock transfer path (0.4.0).
- Done: official SR1.5 Pack B assets integrated; shared 480×480 preview;
  analog/digital speedometer editor with face, needle, mark, bar, unit,
  max-speed and color controls; speedometer bundle builder (0.5.0).
- Done: stock clock and digital speedometer builders (0.6.0). Next: date
  color, digital clock, hand thickness, tick marks and more layout controls.
- Maintenance plans, diagnostics, advanced settings.
- Optional home-screen widget / quick tile for clock sync.

### Never
Account/login, cloud sync, firmware OTA, social.

## Protocol references

Unit tests use local fixture frames
(`app/src/test/.../SunrayProtocolTest.kt`).
