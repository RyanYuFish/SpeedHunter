# ZEEHO Speed Hunter

[](简体中文)

[![GitHub License](https://img.shields.io/github/license/RyanYuFish/Zeeho-SpeedHunter?logo=gnu&logoColor=white)](https://github.com/RyanYuFish/Zeeho-SpeedHunter/blob/main/LICENSE)

An LSPosed module for the ZEEHO App, intended for compatibility research and interface restoration on personal devices.
**Not affiliated with or authorized by ZEEHO / CFMOTO.**

## Features

| #    | Capability                                    | Description                                                  |
| :--- | :-------------------------------------------- | :----------------------------------------------------------- |
| 1    | **Ride record restoration**                   | Displays ride statistics hidden by the server: ride analysis entry, top speed, hard acceleration, hard braking, and cornering |
| 2    | **OTA traffic logging**                       | Read-only observation of the OTA chain; does not forge or rewrite any server-side data |
| 3    | **OTA push console**                          | Pushes update packages to the dashboard, with mandatory six-item consistency checks before any push; if any check fails, pushing is blocked |
| 4    | **Dashboard wallpaper push**                  | Pushes custom wallpapers to the dashboard; supports generating wallpaper packages by cropping gallery images |
| 5    | **Dashboard screen mirroring**                | Mirrors the phone screen to the dashboard; runs independently of the official App |
| 6    | **Vehicle diagnostics**                       | Reads vehicle information, fault codes, ECUs, live data, etc.; write-type operations are disabled by default |
| 7    | **Network direct connection and diagnostics** | Connects to the vehicle Wi-Fi and detects the vehicle address for pushing and diagnostics |

## Prerequisites

- Install and enable the module in Xposed / LSPosed, with the scope limited to the ZEEHO App;
- After enabling, force-stop and reopen the ZEEHO App;
- Some features, such as screen mirroring and wallpaper push, can be used independently without the module.

## Safety and Privacy

- Does not upload ride records, vehicle information, location, or account data;
- Does not modify the ZEEHO APK, bypass account permissions, or change vehicle control logic;
- Speed data is for interface display only and cannot replace the dashboard or serve as a basis for safety decisions;
- **Do not operate the phone or rely on this module’s readings while riding**;
- Flashing firmware carries a risk of bricking the device. Before pushing, make sure all checks pass and ensure sufficient battery, not charging, and not in Ready state;
- Logs may contain personal information such as vehicle identification numbers, ride records, and account fields; keep them secure.

## Unofficial Notice and License

This project has no affiliation, authorization, sponsorship, or cooperation relationship with ZEEHO, CFMOTO, or their affiliates. Related names and icons belong to their respective rights holders. This project is intended only for compatibility research and interface restoration on personal devices. It provides no official support and does not guarantee continued compatibility. Users must ensure they have lawful rights to use the target device and must comply with local laws and manufacturer warranty policies.

Original code and documentation are licensed under **AGPL-3.0**; this license does not apply to ZEEHO / CFMOTO APKs, trademarks, or data content obtained through their interfaces.