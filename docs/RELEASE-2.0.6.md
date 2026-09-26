# Nuvori 2.0.6 preview

Phone version code 46. The Wear companion is unchanged. The phone APK and AAB are in `downloads/`.

- The Android devices page shows authenticated connections, each paired device's current sync state, last contact, last completed exchange, and received/applied progress. Contact history survives an app restart.
- The foreground sync notification shows connected and paired counts without device names or vault contents. Connections are brief, so the connected count returns to zero after an exchange.
- A one-shot manual check now waits while an encrypted transfer is active. The earlier fixed timer could interrupt a long transfer. Sync also stops if this device is no longer an active group member.

Verification: the signed, minified release build and vital lint passed. The APK reports package `com.application.private_vault`, version 2.0.6/code 46, and the same signing certificate as 2.0.5; the AAB signature verifies. The debug build and unit tests passed. Six focused encrypted exchange tests passed on each of the phone and Wear emulators, including protected contact-history persistence. Intermittent Wi-Fi, process restart, interrupted transfers, and the broader sync release matrix still need physical-device testing.
