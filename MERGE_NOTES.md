# AE5i Enhanced branch / PR preparation

Suggested branch: `develop/ae5i-enhanced`
Base repository: `RyanYuFish/Zeeho-SpeedHunter` / `main`

Source: SpeedHunter 3.6.3 full source plus FPS30 adjustments. Includes preexisting mirroring and OTA-related code from that supplied source tree. **No claim is made that server-side OTA permissions are bypassed or that features were validated.**

Changes specifically compared with the supplied 3.6.3 source package:
- `app/src/main/java/com/github/zeehospeedhunter/mirror/ScreenEncoder.java`
- `app/src/main/java/com/github/zeehospeedhunter/mirror/StandaloneMirrorService.java`

This archive is source prepared for application to a fork of upstream, not a Git commit or merged pull request. Review changes and build before opening PR. It intentionally omits previous validation logs/reports.

To submit: fork upstream, create `develop/ae5i-enhanced`, replace/merge appropriate source files, run builds/tests, commit and push; open draft PR against `main`.
