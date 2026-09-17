# Private Vault 1.6.0

## New Android package

This release uses com.application.private_vault for Google Play registration. Earlier releases used com.privatevault.app. Android treats them as separate apps, so installing this release does not move the old vault automatically.

1. In the old app, export an encrypted backup and keep its password.
2. Install this release and create a vault with your chosen master password.
3. Restore the encrypted backup and confirm replacement after validation.
4. Check your cards, passwords, authenticator accounts, groups, notes, photos and app links.
5. Re-enable fingerprint access and any optional permissions, and add the new app's Vault codes tile.
6. Keep the old app and backup until you have verified the transfer.

## Play preparation

- Targets Android 16, API 36, while retaining Android 10 as the minimum.
- Uses Android Gradle Plugin 8.9.2, Gradle 8.11.1 and Build Tools 36.0.0.
- Updates CameraX to 1.4.2 for native library compatibility. SQLCipher 4.6.1 is retained and checked for native alignment.
- Adds a privacy policy available before login and in Settings, plus a web version ready to host.
- Adds a signed Android App Bundle build and a Play submission guide with developer contact details and reviewer instructions.

See [Play submission](PLAY-SUBMISSION.md) before uploading. The privacy page still needs public hosting. This build does not publish the app or enroll a signing key in Play App Signing.

## Verification

- 25 unit tests passed.
- All 7 instrumentation tests passed on Android 14 and again on Android 16 with a confirmed 16,384-byte memory page size. These cover encrypted backup round trips, photos, authenticator parsing and generation, masked copying, biometric expiry, and picker lifecycle protections.
- Lint completed with 0 errors and 46 warnings, including dependency-update suggestions and existing vector-path warnings.
- Bundletool validation passed. The AAB reports PAGE_ALIGNMENT_16K, all 12 bundled native libraries have aligned LOAD segments, and the release APK passed zipalign's 16 KB check.
- AAB and APK signatures verified. The release manifest targets API 36, supports API 29+, disables debugging, and has no Internet permission.
- The signed release launched on the Android 16 16 KB emulator; the bundled policy and support contact were accessible before creating a vault.

Real Samsung fingerprint/NFC behavior, end-to-end Play delivery, and a manual transfer from the old package still need checking with dummy data before public release. The automated restore test exercises the complete backup data path but is not a substitute for that final phone check.
