# Private Vault

An offline Android app for cards, passwords, authenticator codes, security questions, notes, and photos.

No account, server, ads, or cloud sync. Your vault stays on your phone.

<img src="logo.jpg" alt="Private Vault logo" width="160">

## Download

**[Download Private Vault v1.5.0](downloads/private-vault-v1.5.0.apk?raw=true)**

Requires **Android 10 or newer**. Signed release APK, about 77 MB. [SHA-256 checksum](downloads/SHA256SUMS.txt) · [Release notes](docs/RELEASE-1.5.0.md)

Open the download on your phone and allow installation from your browser or file manager when Android asks. No PC connection is needed. Install updates over the existing app to keep your vault; do not uninstall first.

## Features

- **Cards:** credit/debit cards, number, cardholder, expiry, CVV, notes, and photos. Choose from 34 colors and browse a stacked or expanded view. Copy individual fields; revealing or copying a CVV requires biometric authentication.
- **Passwords and security questions:** save login details and answers, with notes and photos.
- **Authenticator:** generate TOTP codes offline. Scan a setup QR, import a QR image, or enter a setup key. Supports SHA1/SHA256/SHA512, 6–8 digits, and configurable intervals. The phone's clock must be correct. SMS, push approvals, HOTP, and Google Authenticator transfer QR codes are not supported.
- **Groups:** keep a bank's cards, logins, questions, notes, and codes together. An entry can belong to several groups. Deleting a group keeps its entries.
- **Photos:** full-resolution capture and original-file import, encrypted thumbnails, crop, rotate, cover selection, and zoom. Imported originals remain outside the vault.
- **Home:** favorites, recently opened entries, expiry warnings, and quick add. Search titles, tags, notes, and group names without indexing passwords, answers, or authenticator keys.
- **Appearance:** light mode or a pure black dark mode, with layouts for phones and larger screens.
- **Optional NFC:** off by default. Start a scan from the card form to fill readable details, then review before saving. Not every card exposes its details. NFC does not provide the printed CVV or make payments.

The bottom tabs are **Home, Cards, Passwords, Codes, and More**. Groups, Security questions, Notes, and Settings are under More.

## How it works

Kotlin and Jetpack Compose handle the interface. Room with SQLCipher stores the encrypted database. AES-GCM encrypts photos in private app storage. The app has no Internet permission.

A random key encrypts the vault. Argon2id derives a key from your master password to protect that vault key. The app does not store the master password itself.

After entering the master password and enabling fingerprint access, strong biometrics can unlock the vault for 24 hours. A **Use fingerprint** button lets you retry the prompt. After expiry, the master password is required again. The phone's PIN is not a vault credential.

The app locks immediately when the screen turns off and after one minute without interaction. Ordinary app switching has a grace period of up to 10 seconds. App-launched camera and file-picker flows use the remaining inactivity time.

Screenshots and recent-app previews are blocked. Android's automatic cloud backup and device transfer are disabled. Copied secrets are marked sensitive where Android supports it; the app attempts to clear its clipboard item after 30 seconds.

There is no password-reset service. This is a personal project, not an independently audited password manager. It cannot protect data from a compromised operating system. Keeping a login password and its authenticator key together means someone who compromises the unlocked vault may get both.

## Back up or move phones

1. Open **More → Settings → Export encrypted backup** and enter the current master password.
2. Save the `.pvault` file somewhere you can access if you lose the phone. Choosing a cloud file provider can upload the encrypted file through that provider.
3. On the new phone, install the app and create a vault.
4. Open **More → Settings → Restore encrypted backup**, choose the file, and enter the password used when that backup was created.
5. Review the validated backup summary, then confirm replacement.

Backups include entries, authenticator keys and settings, groups and links, notes, full photos, cover selections, favorites, ordering, and appearance/NFC preferences. Thumbnails are recreated. Restore replaces the destination's contents; it does not merge two vaults.

The destination vault keeps its own master password. Fingerprint enrollment and the 24-hour biometric session must be enabled again. Changing your master password does not change the password on older backups.

Tink Streaming AEAD encrypts the backup. Restore validates the encrypted file and prepares new encrypted photos before asking to replace the vault. Database changes are committed in a transaction. Keep the old phone and backup until you have checked the restored entries and codes. Keep account recovery codes separately accessible.

## Build from source

Install **JDK 17** and the **Android SDK with API 34**, or open the project in Android Studio. The Gradle wrapper downloads build dependencies; development needs Internet access even though the app does not.

Set the SDK path in your local `local.properties`, for example:

```properties
sdk.dir=C:/Users/YOU/AppData/Local/Android/Sdk
```

Windows:

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

macOS/Linux:

```sh
sh gradlew testDebugUnitTest assembleDebug
```

The debug APK is at `app/build/outputs/apk/debug/app-debug.apk`. It installs separately from the release app.

With an emulator or test phone connected:

```powershell
.\gradlew.bat connectedDebugAndroidTest lintDebug
```

Tests cover TOTP reference codes, QR decoding, photo preservation, database edits, encrypted transfers, damaged/truncated files, cancellation, and restore rollback. Test fingerprints, NFC, and the camera on the phone you intend to use.

See [release signing and download updates](docs/PUBLISHING.md) for signed builds, and [third-party notices](THIRD_PARTY_NOTICES.md) for libraries and artwork.
