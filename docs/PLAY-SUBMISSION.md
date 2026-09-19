# Google Play submission

Developer: Santosh Kanumuri
Support email: santosh.kanumuri@hotmail.com
Account type: personal
Package: com.application.private_vault

## Before the first upload

This release uses the registered package com.application.private_vault. Earlier APKs used com.privatevault.app, so this is a separate installation regardless of signing key. Export an encrypted backup from the old app, create a vault in the new app, restore the backup, and verify the records before removing the old app. Re-add the Quick Settings tile and grant optional permissions in the new app.

Enroll in Play App Signing. To allow updates between the new package's direct APK and Google Play builds, import the existing signing key using the Console's encrypted key-import instructions. Keep the key outside source control; use a separate upload key if desired. If Google generates a different signing identity instead, distribute Google's signed APK for this package going forward. The generated AAB uses the current local release signing configuration; signing an upload is separate from choosing Google's distribution signing key.

The AAB is app/build/outputs/bundle/release/app-release.aab. Upload it to Internal testing first. Confirm its package, version, and signing setup before rolling out. Do not uninstall an existing vault to fix a signature mismatch; export an encrypted backup first.

## Store listing copy

Name: Private Vault

Short description: Offline vault for cards, passwords, notes and authenticator codes.

Full description:

Keep your cards, passwords, security questions, notes, photos and authenticator codes on your Android device.

Private Vault works offline. There is no server account, advertising, analytics or automatic cloud sync. A master password protects your encrypted vault. Strong biometrics can unlock it during a 24-hour session after password entry and biometric confirmation.

Save credit and debit card details, choose card colors, and browse a stacked card view. Organize related cards, logins and questions into groups. Add photos, crop or rotate them, and create encrypted backups to move your vault to another phone.

Generate time-based authenticator codes from a setup QR or manual key. Add the Vault codes Quick Settings tile to copy a code without changing your password autofill app. Optional Usage access suggests accounts linked to the previous app. Codes in the picker start masked, with separate Copy and Show controls.

Optional Autofill fills supported native apps and exact HTTPS websites in verified Chrome and Brave releases after authentication and account selection. Select Private Vault as your Autofill provider to use it. Import Chrome/Brave password CSV files with a preview. Supported signup and password-change forms can generate and save a separate login before filling. Browser Save/Update prompts require your confirmation. On Android 14 or newer, create and use ES256 passkeys for supported websites in verified browsers. Passkeys are included in encrypted backups. Existing passkeys cannot be imported from password CSV files; native-app and related-domain passkey requests are not supported.

Optional NFC scans fill supported card details for review. This app does not make payments, provide banking services, or replace a payment wallet.

Your master password cannot be recovered. Keep encrypted backups and account recovery codes somewhere safe. A correct device clock is required for authenticator codes. Imported photos and exported backups remain wherever you originally saved them.

Support: santosh.kanumuri@hotmail.com

Suggested category: Tools. Choose the truthful target audience and complete the content-rating questionnaire. No ads, purchases, or subscriptions are implemented. Do not claim an independent security audit or guaranteed protection.

## Privacy and App content

The policy is bundled in the app and available before unlock and in Settings. Publish docs/privacy-policy.html on a public website before submission. If GitHub Pages is enabled for this repository's docs folder, its expected URL is https://santoshkanumuri.github.io/vault_apk/privacy-policy.html. This URL is not valid until you publish and verify it while signed out.

Review Data safety against the final build and all dependencies. The app has no Internet permission and processes vault information locally. On-device-only processing is not off-device collection under Google's definition. Manual exports and clipboard transfers are user-directed. Answer the form for actual collection/sharing behavior, not the mere presence of sensitive fields. Support email is outside the app. Do not claim a server account or a server account deletion service; the vault is local. Explain local deletion and separately retained backups.

Usage access is optional and used only when the tile opens. The Settings disclosure describes recent app activity, its purpose, and no stored usage history before opening Android's permission screen. Camera and NFC are optional. Complete any declarations Console requests. For financial features, describe local storage only: no transactions, financial advice, banking or lending. Review the actual questionnaire rather than assuming a category grants exemption.

## App access instructions for reviewers

No developer-provided login is needed. On first launch, create a local vault with a new master password of at least 12 characters and confirm it. Use only dummy information. There is no email verification or server account.

To test authenticator codes, open Codes, add an account manually, and use the public dummy setup key JBSWY3DPEHPK3PXP. It is not connected to a real account. Keep the default SHA1, 6 digits, 30-second interval. Edit Linked apps to select a test app.

Open More > Settings to export and restore an encrypted backup. Export requires the current password. Restore validates the backup before offering replacement. Use the exported backup password, not a developer credential.

Settings now groups these actions under Backup and import. Autofill and codes contains the provider selection and tile controls. For native autofill, authorize a dummy app in a saved login's editor. For browser filling, save the exact HTTPS URL and enable the browser's third-party autofill option. Browser fills require a supported release signing certificate; different subdomains do not match.

Biometric features need a device with a strong biometric enrolled. They are optional for basic vault access; use the master password otherwise. CVV reveal requires biometric authentication. NFC scanning needs compatible NFC hardware and a supported physical card; manual card entry remains available. The app never reads the printed CVV through NFC.

For the shortcut, add Vault codes from Settings or Android's Quick Settings edit screen. It asks for authentication separately. To test optional app suggestions, turn them on and grant Usage access, open a linked app, then tap the tile. Copy works while masked. Show all displays all accounts. Browsers identify only the browser, not websites.

## Testing and publication

1. Complete account identity/device verification tasks shown in Console.
2. Upload the signed AAB to Internal testing and test installation and upgrade with dummy data.
3. Add real store screenshots using dummy information, a 512 x 512 app icon, and a 1024 x 500 feature graphic. These listing images are not generated by the build.
4. Complete privacy, Data safety, App access, ads, audience, content rating and other requested declarations.
5. For a personal account created after November 13, 2023, run a closed test with at least 12 testers continuously opted in for 14 days. Gather real feedback and fix issues before applying for production access.
6. After access is granted, choose countries and pricing and submit a production release for review. No publication has been performed by this project setup.

Sources: [target API](https://support.google.com/googleplay/android-developer/answer/11926878), [Play App Signing](https://support.google.com/googleplay/android-developer/answer/9842756), [testing](https://support.google.com/googleplay/android-developer/answer/14151465), [App content](https://support.google.com/googleplay/android-developer/answer/9859455), [16 KB support](https://developer.android.com/guide/practices/page-sizes).
