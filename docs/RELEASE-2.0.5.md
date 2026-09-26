# Nuvori 2.0.5 preview

Phone version code 45. The Wear companion is unchanged. The phone APK and AAB are in `downloads/`.

- Android device sync now tries current Wi-Fi discovery before a saved address, refreshes failed endpoints, and lets either paired device start the connection. An authenticated peer updates its saved address.
- The Android devices page puts sync status and controls first. It distinguishes discovery, connection, transfer, received changes, completed checks, and failures, and clarifies per-device received and applied progress.
- Saved changes can prompt an active sync service to check sooner. A transfer that stops partway reports that more changes remain instead of claiming the check finished.

Verification: the debug build and unit tests passed. Six focused encrypted exchange tests passed on each of the phone and Wear emulators. The signed release artifacts were built and checked for version, package, signature, and SHA-256. Intermittent Wi-Fi, process restart, and interrupted transfer still need testing on two physical Android devices. The broader Android sync release checks in `sync-steps.md` remain open.
