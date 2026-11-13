# Nuvori 2.1.6

Android version code 57. Windows version 2.1.8. Browser extension version 0.7.1.

## Sync

- **Busy replies.** Windows 2.1.6 and earlier answered `busy` while syncing with another device; the phone expected `busy1` and showed "Sync needs attention". The phone now accepts both, and Windows 2.1.7 sends `busy1`. Both sides then retry after a few seconds instead of backing off.
- **Removed devices.** Changes from a device that left the group are no longer sent to devices that would reject them, which ended the whole sync session. When you remove a device, the phone can now record that device's last change in the signed removal; every device then accepts and passes on its changes up to that point and never later ones. This turns on only when every other device announced support in the sync handshake. See [Personal device sync](PERSONAL-DEVICE-SYNC.md#removing-a-device-in-216).
- **Remove dialog.** It names each paired device that has not received every change from the device you remove, so you can sync it first.
- **Handshake features.** The phone and Windows announce what they support in the sync handshake. Older builds ignore the list, and new behavior turns on only between devices that both support it.

## Autofill

- A phone-number box before a lone password is used as the account name, as many sites sign in with a phone number. A name or address box still is not.
- A web field named exactly `address` is filled as the street line.
- The phone and the Windows browser extension share 68 field test cases, so both read login, code and address fields the same way.

## Windows 2.1.8 and browser extension 0.7.1

Released together with this phone version. Highlights: the PC applies changes that point to deleted folders or codes, sets aside an unreadable change instead of failing every sync, uses the phone's sync status words and per-device counts, and answers leave requests. The extension follows two-step sign-ins, offers logins saved for another page of the same site, and fills card, address and code fields inside payment frames. Windows 2.1.8 also connects the browser extension by itself, with no PowerShell step. See the Windows repository's `docs/RELEASE-2.1.7.md` and `docs/RELEASE-2.1.8.md`. Windows 2.1.9 followed with the same sync behavior; it adds a passkey status row in Settings and lets the Store build take over from the installer build (`docs/RELEASE-2.1.9.md`).

## Verification

- 304 Android JVM tests passed (296 app, 8 watch common). New coverage: busy replies from both platforms, the removal cutoff format and its accept rule, relaying only active authors, the removal warning, and the shared field and username vectors.
- Release lint reported 0 errors and 78 warnings.
- Signed APK and AAB builds passed. The APK reports version 2.1.6, code 57, and its signer certificate (SHA-256 `c88756b1…a6db79d9`) matches 2.1.5. APK signature scheme v2 verified, zip alignment passed, and all 12 native libraries in each package passed the 16 KB alignment check. The AAB signature verified with `jarsigner`.
- Windows: 170 Rust tests (4 network and emulator tests ignored), 43 browser host tests and 121 extension tests passed.

Not run for this release: Android device tests on an emulator or phone, and any physical check. In particular, a removal between real devices, sync between this phone build and Windows 2.1.8, and autofill on real sites have not been verified.

## Download files

- [Phone APK](../downloads/nuvori-v2.1.6-phone.apk) for updating Android phones.
- [Phone AAB](../downloads/nuvori-v2.1.6-phone.aab) for Google Play upload.
- [R8 mapping](../downloads/nuvori-v2.1.6-phone-mapping.zip) for decoding crash reports.
- [Android checksums](../downloads/SHA256SUMS-2.1.6.txt).
- Windows setup EXE, MSI and extension zip are in `card_app_windows/artifacts/`.

## Updating

Order does not matter: older and newer builds keep syncing, and new behavior turns on once both sides are updated.

1. Install the 2.1.6 APK on each phone as an update. Keep the existing vaults and pairing.
2. On the PC, back up `%APPDATA%\com.nuvori.desktop`, quit Nuvori from the tray, and run the 2.1.8 package of the type you installed before (MSI or setup EXE).
3. Replace the extension folder's files with `Nuvori_extension_0.7.1.zip`, reload it, open Nuvori, and restart the browser. Nuvori connects the extension by itself.
4. Tap **Sync now** on a phone and check that a small edit reaches the PC and back.

If a check fails, record the top sync status and the device-card message on both devices. Do not clear a vault to retry a connection.
