# Nuvori 2.0.7 preview

Phone version code 48. Signed phone APK and AAB are in `downloads/`. The Wear companion is unchanged.

- Android devices now uses plain states for looking, verifying, syncing, waiting, and completed checks. Device cards explain where known changes are waiting. Advanced settings shows received and applied counts, last contact, and connection help. Counts reflect the last authenticated contact; another device can have newer changes that have not reached this one.
- New pairing requires the same master password on both devices before the vault copy starts. The empty joining device can change its local password within the pairing flow. A paired device cannot change its password alone. The managing device shows the QR; the new device scans it and both confirm the matching code.
- First-run setup offers Join an existing vault and opens the pairing screen after creating an empty local vault. The intro now explains local Wi-Fi sync. Settings has a Help page with expandable answers and shortcuts for sync, autofill, Quick Settings, passkeys, security, appearance, NFC, and imports.
- The current automatic sync service and notification remain in place. A successful check reports what it knew at that time; it does not prove that an offline device has no newer edits. Devices paired on one Wi-Fi can sync later on another local Wi-Fi that permits device-to-device connections.

Verification: debug compilation, unit tests, release lint, and 10 focused phone-emulator UI tests passed. The tests covered sync states, Help navigation, onboarding choices, and wrong-password rejection before a pairing invitation. The signed release APK installed on the phone emulator, and APK version and signatures, AAB signature, and checksums were checked. Two-device physical pairing, password matching during a full vault transfer, intermittent Wi-Fi, and release installation on physical devices still need checks.
