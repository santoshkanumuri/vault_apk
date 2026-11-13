# Nuvori 2.2.0

Android version code 58. Windows version 2.2.0. Browser extension version 0.7.1 (unchanged).

## Sync through Google Drive

- **New card:** **Settings > Devices & sync** has a card named **Sync when your devices are apart**. It syncs your paired devices through your own Google Drive when they are not on the same Wi-Fi.
- **Optional:** Wi-Fi sync works as before, with or without Google Drive.
- **How to connect:** tap **Connect Google Drive** and choose your Google account. Then connect the same account on your other devices. On Windows, use the same card on the **Sync** page.
- **One permission:** Nuvori asks only for its own hidden app folder in your Google Drive. It cannot see or change your other files.
- **Encrypted:** the phone encrypts every change and photo before it uploads it. Google cannot read your items. Google can see how many files there are, their sizes and their times.
- **No push:** the phone checks Google Drive when you unlock it, about every minute while Nuvori is open, and on **Sync now**. It uploads its own changes right after each change.
- **Same checks as Wi-Fi:** every downloaded change must pass the same signature and hash chain checks as a change received over Wi-Fi. A changed or foreign file is ignored.
- **Own changes kept:** while Google Drive sync is on, the phone keeps its own changes until Google Drive has them, even after the other devices received them over Wi-Fi.
- **Disconnect:** stops Google Drive sync on this phone. The encrypted files stay in your Drive for your other devices.
- **Sign in again:** if Google stops accepting the sign-in, the card shows **Sign in again**. Nothing is lost.
- **First pairing:** it still happens on the same Wi-Fi.

See the Windows repository's `docs/CLOUD-SYNC.md` for what Google can see, the file format and the limits.

## Privacy policy

The [privacy policy](privacy-policy.html) now describes Wi-Fi sync and Google Drive sync. The earlier text said that the app had no Internet permission. That was wrong since 2.0.0, which added Wi-Fi sync. The introduction screen now says that the vault needs no Nuvori account and that Google Drive sync is optional.

## Windows 2.2.0

Released together with this phone version. It adds the same Google Drive card on the **Sync** page. See the Windows repository's `docs/RELEASE-2.2.0.md`.

## Verification

- 312 Android JVM tests passed in a fresh run (304 app, 8 watch common). New coverage: the `nv1` file names and envelope against the shared vectors, names from other groups, and the sync pass through an in-memory Google Drive (two devices, forged and misnamed files, a long history, a signed removal).
- Release lint reported 0 errors and 79 warnings.
- Signed APK and AAB builds passed. The APK reports version 2.2.0, code 58, and its signer certificate (SHA-256 `c88756b1…a6db79d9`, SHA-1 `3702b3b5…bab3e62c`) matches 2.1.6. APK signature scheme v2 verified, zip alignment passed, and all 12 native libraries in each package passed the 16 KB alignment check. The AAB signature verified with `jarsigner`.
- Windows: 187 Rust tests (4 network and emulator tests ignored), 43 browser host tests and 121 extension tests passed.

Not run for this release: Android device tests on an emulator or phone. In particular, a live Google sign-in, a real Google Drive call, sync between this phone build and Windows 2.2.0 through Google Drive, and the Google Drive card on a real screen have not been verified.

## Download files

- Phone files: see [2.2.1](RELEASE-2.2.1.md), which replaced these.
- Windows setup EXE, MSI and Store test package are in `card_app_windows/artifacts/`.

## Updating

Order does not matter. Older and newer builds keep syncing over Wi-Fi. Google Drive sync works only between 2.2.0 devices.

1. Install the 2.2.0 APK on each phone as an update. Keep the existing vaults and pairing.
2. On the PC, quit Nuvori from the tray, and run the 2.2.0 package of the type you installed before (MSI or setup EXE).
3. On the phone, open **Settings > Devices & sync** and tap **Connect Google Drive**.
4. On the PC, open **Sync** and select **Connect Google Drive**. Use the same Google account.
5. Turn off Wi-Fi on the phone. Edit an item on the phone, and check that it reaches the PC. Then edit on the PC and check the phone.

If a check fails, record the message on the Google Drive card on both devices. Do not clear a vault to retry a connection.
