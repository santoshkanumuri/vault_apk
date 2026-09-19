# Private Vault 1.9.0

Version code: 26. Package: `com.application.private_vault`.

## Changes

- Added folders for cards, passwords, questions, and notes. Folders appear before unfiled entries. Each entry shows its type and folder. Deleting a folder leaves its entries in the vault.
- Search now covers usernames, question text, card last four digits, folder and group names, tags, and notes across the vault. Passwords, answers, CVVs, full card numbers, and authenticator secrets are excluded.
- Entry editors scroll independently while Save and Cancel stay above the keyboard. Back controls use an icon.
- Passkeys can be created and used by native Android apps through Credential Manager on Android 14 and newer. The provider binds responses to the installed app's signing certificate and shows the app and requested site before confirmation. Existing verified Chrome and Brave support remains.
- Browser autofill recognizes clearly named username and email fields without standard autofill hints, including Seedr's `name="username"` login field.

## Backup and update

Install over v1.8.0 with the same signing key. Database migration adds folder type to existing groups without removing entries. New encrypted backups use metadata version 6 and include folders and links. This version reads earlier backups. Use v1.9.0 or newer to restore backups exported by v1.9.0.

Export an encrypted backup before updating. Keep it until you have checked the updated vault.

## Checks and limits

Unit tests, Android lint, and Android 16 emulator checks covered folder backup and restore, native app passkey origins and signatures, and compact editor actions with enlarged text. The APK was checked for release signing, native alignment, no Internet permission, and debugging disabled.

Native app passkeys depend on the requesting app using Android Credential Manager and its server accepting the signed Android app origin. Some apps only offer selected providers or need passkey extensions this vault does not support. WhatsApp account sign-in still needs a real-phone check. Browser passkeys continue to require a verified Chrome or Brave build and an exact HTTPS host.
