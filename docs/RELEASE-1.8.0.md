# Private Vault 1.8.0

Version code: 25. Package: `com.application.private_vault`.

## Changes

- Autofill shows the Private Vault icon and name. Compatible keyboards can show an unlock suggestion; other keyboards use the dropdown.
- Browser login saving and updates require authentication and confirmation. Updates preserve groups, photos, notes and authenticator links. Username-first login pages require a separate account selection on each page.
- Supported signup and password-change forms can generate a 24-character password. **Save new login and fill** saves it before filling, because Android may not offer a save prompt for an unchanged autofilled password. The generated login keeps the selected account's groups and authenticator link. The old login stays unchanged. Submit the website form yourself, then check that it accepted the password before removing an older login.
- Android 14 and newer can use Private Vault as a passkey provider. Enable it under Settings > Passkeys. Creation and sign-in require vault authentication and confirmation. Private keys stay in the encrypted vault and are included in encrypted backups.
- Settings > Passkeys lists saved passkeys and lets you delete them. Deletion does not remove the website registration or copies in older backups.
- Detail pages have less top padding and respect the screen's safe area. Bottom actions can grow with enlarged text. The autofill picker uses more of the available height.

## Backup and updates

Install over v1.6.0 or v1.7.0 using the same signing identity. Database migrations preserve existing records and add passkey storage. Export an encrypted backup before updating.

Passkeys are included in version 5 backups. Use v1.8.0 or newer to restore these backups. Older backup formats remain readable. The new phone keeps its own master password and needs its Autofill and passkey providers enabled again.

## Checked

- 31 unit tests passed; Android lint passed with warnings.
- Device test suites passed on Android 14 and Android 16 with 16 KB pages. Checks include encrypted backup transfer, wrong passwords, damaged backups, migration, cancellation, native autofill, and compact detail pages with enlarged text.
- Live Brave checks passed for signup generation and password changes, with the saved password compared to the submitted value and the old login preserved.
- A live Brave passkey check created a credential, exported it, restored it into a fresh vault with a different encryption key, and signed in successfully with the restored key.
- Release signing, APK ZIP alignment, and native library alignment passed. The APK has no Internet permission and is not debuggable.

Browser tests used synthetic accounts on fill.dev. Real-device fingerprint behavior and current Chrome still need phone checks.

## Limits

Website autofill is restricted to verified Chrome and Brave and exact HTTPS origins. Ambiguous forms and frames are rejected. Password history and saving credentials spread across multiple pages are not implemented.

Passkeys currently support ES256, anonymous attestation, and an exact website-host match. Native-app passkeys, related or parent-domain requests, additional algorithms/extensions, and importing passkeys from other managers are not supported. Some browsers require Private Vault as the Autofill provider with third-party autofill enabled as well as the passkey-provider setting.

These tests do not replace an independent security audit. Keep account recovery codes separately accessible.
