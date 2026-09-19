# Private Vault 1.7.0

Version code: 24. Package: `com.application.private_vault`.

- Optional Android autofill for explicitly authorized native apps. App package and signing identity must match. Unlock and choose an account before filling.
- Website autofill for supported HTTPS forms in verified Chrome and Brave release builds. The saved website and requesting origin must match exactly. Enable third-party autofill in the browser as well as choosing Private Vault in Android Settings.
- Link an existing authenticator to a login. Its code appears with the login and can fill a separate OTP screen. The Codes tab and Quick Settings tile remain available.
- Generate a 24-character password locally in the login editor.
- Import Chrome/Brave password CSV files with a preview. Exact duplicates are skipped; differing passwords are kept separately. Imports commit together and never overwrite existing records. Delete the original readable CSV after checking the import.
- Settings now has separate pages for Security, Autofill and codes, Backup and import, Appearance, Cards and NFC, and About. More groups vault sections separately from app settings.
- Encrypted backups include login/authenticator links, website URLs and native-app authorizations. The database migration preserves existing records.

No Internet permission was added. Automatic save/update, username-only multi-step forms, keyboard-inline suggestions, other browser certificates, passkeys, desktop apps and sync are not implemented. Browser forms with frames, multiple forms or ambiguous fields are rejected.

Tests cover exact app/domain matching, password generation, CSV quoting and malformed files, import rollback, database migration, encrypted backup transfer, and Android's actual native-app password/TOTP flow. A live Brave 1.95.102 HTTPS form was filled with dummy credentials on the Android 16 emulator. Chrome is supported by the certificate/origin checks but still needs a current-version device check. Real fingerprint behavior needs verification on the phone.

Passkey creation, portable encrypted backup and restored sign-in have their own acceptance milestone in [the plan](PASSWORD-MANAGER-PLAN.md). This release does not create or store passkeys.

Release checks passed: 31 unit tests, 11 instrumented tests on each emulator, lint with zero errors, APK signing verification, APK/AAB native alignment, APK ZIP alignment, and bundletool validation. The APK signing certificate matches v1.6.0. An update install over v1.6.0 succeeded on the Android 16 emulator. No Internet permission or debugging flag is present in the release APK.
