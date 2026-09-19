# Private Vault

An offline Android app for cards, passwords, authenticator codes, passkeys, security questions, notes, and photos.

No account, server, ads, or cloud sync. Your vault stays on your phone.

<img src="logo.jpg" alt="Private Vault logo" width="160">

## Download

**[Download Private Vault v1.9.2](downloads/private-vault-v1.9.2.apk?raw=true)**

Requires **Android 10 or newer**. [SHA-256 checksum](downloads/SHA256SUMS.txt) · [Release notes](docs/RELEASE-1.9.2.md) · [Privacy policy](docs/privacy-policy.html)

Open the download on your phone and allow installation from your browser or file manager when Android asks. No PC connection is needed.

**Moving from v1.5.2 or earlier:** v1.6.0 uses the new package `com.application.private_vault` and installs as a separate app. Export an encrypted backup from the old app, restore it into the new app, and check your records before removing the old installation. Later updates using the same package and signing identity can install over this version.

For Google Play, use the AAB and [submission guide](docs/PLAY-SUBMISSION.md). Package registration is separate from publishing a store listing.

## Features

- **Cards:** credit/debit cards, number, cardholder, expiry, CVV, notes, and photos. Choose from 34 colors and browse a stacked or expanded view. Copy individual fields; revealing or copying a CVV requires biometric authentication.
- **Passwords and security questions:** save login details and answers, with notes and photos.
- **Linked logins:** generate a 24-character password locally and link an existing authenticator to a login. View its code alongside the login without duplicating the setup key. Removing the login keeps the authenticator.
- **Optional autofill:** authorize native apps in a login's editor or save its exact HTTPS website URL, then choose Private Vault as your Autofill provider. Unlock and select an account to fill a username/password or a linked code on a separate code screen. Native apps require the saved package and signing identity. Website filling is restricted to verified Chrome and Brave release certificates and an exact HTTPS origin match. Browser support depends on Android autofill being enabled in the browser. The unlock suggestion shows the app icon and name, including inside compatible keyboards; short screens use a regular Android suggestion. Native-app and browser Save/Update prompts require a separate vault unlock and confirmation. Choosing this provider replaces your current password autofill provider; the code tile remains available if you prefer to keep Bitwarden.
- **Browser password import:** import a Chrome or Brave password CSV under Settings > Backup and import. Repeated rows are consolidated, exact matches are skipped, and changed passwords are shown for review. Choose whether to keep saved passwords or replace only their password fields; names, notes, folders, photos, and linked codes remain intact. The original CSV contains readable passwords; delete it after verifying the import.
- **Authenticator:** generate TOTP codes offline. Scan a setup QR, import a QR image, or enter a setup key. Supports SHA1/SHA256/SHA512, 6–8 digits, and configurable intervals. The phone's clock must be correct. SMS, push approvals, HOTP, and Google Authenticator transfer QR codes are not supported.
- **Groups:** keep a bank's cards, logins, questions, notes, and codes together. An entry can belong to several groups. Deleting a group keeps its entries.
- **Folders:** organize cards, passwords, questions, and notes inside their own categories. Folders appear before unfiled items, and entry cards show their folder. Deleting a folder keeps its entries. Folders and links are included in encrypted backups.
- **Quick access to codes:** add the Vault codes tile from More > Settings. Unlock, choose an authenticator account, and copy its current code without changing your password autofill app. The picker closes on backgrounding and follows the same 24-hour fingerprint rule.
- **App-linked codes:** choose Linked apps when editing an authenticator account. Optional Usage access lets the tile suggest accounts for the previous app. Codes start masked, with Copy, Show/Hide, and Show all controls. App links are included in encrypted backups. Browser websites cannot be identified this way.
- **Photos:** full-resolution capture and original-file import, encrypted thumbnails, crop, rotate, cover selection, and zoom. Imported originals remain outside the vault.
- **Home:** favorites, recently opened entries, expiry warnings, and quick add. Search across entry types, usernames, questions, card last four digits, folders, groups, tags, and notes. Passwords, answers, CVVs, full card numbers, and authenticator keys stay out of search.
- **Appearance:** light mode or a pure black dark mode, with layouts for phones and larger screens.
- **Optional NFC:** off by default. Start a scan from the card form to fill readable details, then review before saving. Not every card exposes its details. NFC does not provide the printed CVV or make payments.

The bottom tabs are **Home, Cards, Logins, Codes, and More**. Logins contains passwords. Groups, Security questions, Notes, and Settings are under More.

Settings has separate Security, Autofill and codes, Passkeys, Backup and import, Appearance, Cards and NFC, and About pages.

Signup and password-change forms with explicit new-password fields can generate a password locally. Confirm **Save new login and fill** to save it before filling. The generated login keeps the selected account's groups and authenticator link; the old login stays unchanged. Submit the website form yourself and confirm it accepted the new password before removing an older login.

Passkeys require Android 14 or newer. Enable Private Vault under **Settings > Passkeys**. Verified Chrome and Brave requests use the exact HTTPS website host. Native Android app requests use the app's signing certificate as their origin; the confirmation screen shows the app and requested site. The app's server must accept that origin. Passkeys use ES256. Parent-domain and related-origin browser requests, importing existing passkeys, and extra passkey extensions are not supported. Some apps only offer selected passkey providers. Some browsers also require Private Vault as the Autofill provider with third-party autofill enabled.

Password history and saving credentials spread across multiple pages remain unsupported. Username-first filling authenticates and selects an account on each page.

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

Backups include entries, passkey private keys and metadata, authenticator keys and settings, groups, folders and links, notes, full photos, cover selections, favorites, ordering, and appearance/NFC preferences. Thumbnails are recreated. Restore replaces the destination's contents; it does not merge two vaults.

The destination vault keeps its own master password. Fingerprint enrollment and the 24-hour biometric session must be enabled again. Changing your master password does not change the password on older backups.

Login-to-authenticator links and autofill authorizations are included. Choose the Autofill and passkey providers again on the new phone. Use v1.8.0 or newer to restore backups containing passkeys. An app with a different signing identity needs explicit reauthorization in the login editor.

Tink Streaming AEAD encrypts the backup. Restore validates the encrypted file and prepares new encrypted photos before asking to replace the vault. Database changes are committed in a transaction. Keep the old phone and backup until you have checked the restored entries and codes. Keep account recovery codes separately accessible.

## Build from source

Install **JDK 17**, **Android SDK API 36**, and **Build Tools 36.0.0**, or open the project in Android Studio. The Gradle wrapper downloads build dependencies; development needs Internet access even though the app does not.

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

Tests cover browser signup/password changes, passkey signatures and sign-in after encrypted restoration, compact detail layouts, TOTP reference codes, QR decoding, photo preservation, database edits, encrypted transfers, damaged/truncated files, cancellation, and restore rollback. Test fingerprints, NFC, and the camera on the phone you intend to use.

See [release signing and download updates](docs/PUBLISHING.md) for signed builds, and [third-party notices](THIRD_PARTY_NOTICES.md) for libraries and artwork.

The [password manager plan](docs/PASSWORD-MANAGER-PLAN.md) tracks Android autofill, desktop apps, browser integration, and local sync as separate phases.
