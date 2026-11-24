# Nuvori 2.3.4

Android version 2.3.4, code 75. Wear OS companion version 2.1.3, code 360013. Pairs with Windows 2.3.1 and browser extension 0.7.9.

**Update the phone and every PC together.** The new sync features below turn on only when every device in a sync group announces them, so a group with an older device keeps the 2.3.3 behaviour until that device updates. See "Mixed versions" at the end.

## Rich notes

Notes can have formatting: bold, italic, underline, strikethrough, inline code, text and highlight colors, links, three heading levels, bulleted, numbered and check lists with nesting, quotes, code blocks, dividers and tables. Notes can be pinned.

- The formatting is stored next to the note's plain text, in a format both apps share (`docs/RICH-NOTES-PLAN.md`, tested against `docs/rich-notes/fixtures.json` in both repositories). Search, the watch, backups and older versions keep reading the plain text.
- Colors are named tokens (red, blue and so on), so a note reads well in light and dark themes on both devices.
- When the same note is edited on two devices, the conflict review keeps both versions.

## Sign-in methods

When you sign in to a website with Google, Apple, Microsoft, GitHub, Facebook, X, LinkedIn, Discord, Amazon or Slack on your PC, the browser extension can remember which one you used and with which account. These records now sync to the phone.

- **Settings > Sign-ins** lists them, with provider, account and site filters.
- On a site's login page, autofill shows a small "Last time: GitHub · name" reminder with the provider's logo. It never fills a password. Turn it off in **Autofill and codes > Notices**.

## Sync

- **Website icons** that Nuvori for Windows captures now sync to the phone (protocol `site-icons-v1`).
- **New devices join after years of history.** A new device gets the current items and a signed checkpoint instead of the full history (`checkpoint-enroll-v1`). Before, joining failed after about a year of heavy use.
- **History cleanup.** Each device drops sync history that every active device has applied, and old sync files are removed from Google Drive (`history-compaction-v1`). An offline device holds cleanup back.
- **Removing a device changes the group's keys** (`epoch-keys-v1`). A removed device can't read anything made after its removal, over Wi-Fi or Google Drive. This passed an independent security review after its findings were fixed.
- **Lost managing phone.** "Mark as lost" freezes the old group, and another phone can start a new group that the other devices join with their items, without duplicates. Items that differ become conflicts to review.
- A device that left or was removed can pair again as a new device. **Leave and empty this device** is new.
- A device not seen for months gets a suggestion to remove it. Nothing happens automatically.

## Security

- **Passkeys are bound to the asking app or website** (review H-01). A website may use a passkey only for its own domain. An app needs the site's `assetlinks.json` to name it. To check an app, the phone fetches that file from the passkey's website, so that website learns a passkey was used. Offline, an app that wasn't checked in the last 6 hours isn't offered a passkey.
- **Unverified autofill** (an app or browser Nuvori can't verify) no longer offers cards or suggested logins (M-01).
- **Wrong master passwords:** after 5 in a row, each try waits longer, from 30 seconds up to 15 minutes, also after a restart (L-03).
- The card security code (CVV) check is tied to the fingerprint prompt (L-08). The camera cache is cleared whenever a capture flow ends (L-09). BouncyCastle 1.84 (L-01).
- **Watch 2.1.3:** a copied code is cleared from the watch clipboard after 30 seconds (M-02), and the watch accepts a new pairing only from the phone that paired it.
- **The vault key is also sealed by this phone's secure hardware** (M-04): the password-wrapped vault key file is encrypted again with an Android Keystore key, so a copy of the app's files alone can't be attacked with password guesses. Existing vaults move to this format at the first unlock after the update. Encrypted backups keep a password-only wrap, so they still restore on another phone.
- **Photos use their own key** derived from the vault key (L-10). Existing photos are encrypted again with it after the first unlock; photos sync and back up as before.
- Fingerprint unlock lasts at most 7 days before the master password is needed again, and Keystore enforces the end (L-04). The master password is never turned into a `String` (L-11).
- **SQLCipher 4.20.0** (from 4.6.1), with 16 KB memory page support (L-02). The app is about 13 MB smaller.
- After a master password change, Google Drive backups made with the old password are deleted (L-16).
- Pairing on a rooted phone, or with a debugger attached, shows a warning first (L-12).
- Wi-Fi sync checks a connecting device's proof before it can hold up the connection (L-06). The autofill profile copy is kept under a key that needs the phone unlocked (L-05).

## Interface

- The app draws edge to edge, and the status bar icons follow light and dark mode.
- An item's page has **Edit** in the top bar and its other actions (add photo, camera, duplicate, delete) in a menu, instead of the pull-up panel.
- Controls use square 8 dp corners, card labels are larger, copy targets are 48 dp, and light-mode dividers are darker.
- Clearer wording across the app: "Couldn't …" messages, "fingerprint or face unlock", the restore steps, help text and the About page's sync description. Changing the master password asks you to type the new one twice.
- **Watch 2.1.3** uses Wear Compose Material, and its first screen says where to connect it on the phone.
- All on-screen text of the phone and the watch is in `strings.xml`, ready for translation.

## Reliability and other changes

- A restore no longer gets lost when the vault auto-locks while the file picker is open. A restore on a phone in a sync group warns first and leaves the group cleanly.
- Fixed crashes when the vault locks while a screen refreshes.
- The card face hides the number until **View**. "Codes" is used everywhere instead of "TOTP". **Clear Most used** is in **Autofill and codes**.

## Mixed versions

- Windows 2.3.0 and older keep working, but an edit made there removes a note's formatting (the text stays). Update the PC before you use rich notes.
- **Known issue:** editing a note on Windows (also 2.3.1) unpins it, because Windows writes notes as not pinned (`src-tauri/src/vault.rs`). Pin notes again on the phone after editing them on the PC until a Windows fix ships.
- Features marked with a protocol name above stay off in a group until every device has 2.3.4 or Windows 2.3.1.
- **Database version 27, and the new vault key format.** The update migrates the vault on first unlock. An older APK can't open it afterwards.

## Checks

- Unit tests: 669 pass.
- Upgrade tests on the emulator: a vault and a note made on the previous build still open after updating to the SQLCipher 4.20.0 build; a vault, a note and a photo made before the vault key change still open after it, the key file moves to the hardware format (version 3), the photo moves to the new photo key, and a later unlock and the photo work.
- The release APK was installed on the emulator: create a vault, a formatted and pinned note, the entry menu, auto-lock, a wrong and then the right master password, and the Devices, Passkeys and Settings screens, with no crash.
- Instrumented tests on an Android 16 emulator: all sync, migration, passkey, autofill and UI classes pass. Known test problems, the same on the previous main: `PickerAndRestoreUiTest.aBackupChosenWhileTheVaultLockedContinuesAfterUnlock` is flaky, and three `TabletNavigationTest` checks fail (two "Nuvori logo" nodes).
- The watch's instrumented tests compile; they were not run on a Wear OS emulator.
- Release lint passed for phone and watch. Both are signed with the release key (SHA-256 `c88756b1…6db79d9`).

## Downloads

`downloads/nuvori-v2.3.4-phone.apk` and `downloads/nuvori-v2.1.3-watch.apk`. Checksums: `downloads/SHA256SUMS-2.3.4.txt`. The AABs for Google Play and the R8 mapping files are kept outside the repository (security review L-13), next to the signing key.
