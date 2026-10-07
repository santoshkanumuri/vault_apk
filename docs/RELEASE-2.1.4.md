# Nuvori 2.1.4

Phone version 2.1.4 uses version code 55. Windows is also 2.1.4. The watch stays at 2.1.1. A sync group supports six mobile devices, including the managing phone, and two Windows PCs.

## Pairing and sync corrections

- **Android-to-Android pairing no longer stops while the other phone scans.** The phone showing the QR locked its vault after 60 seconds without a touch, or as soon as its screen turned off. Locking cancelled pairing and closed the QR listener. The other phone then reported "QR read, but this device cannot reach the other device". While pairing is in progress, from showing the QR through the full copy, the screen now stays on and the inactivity lock waits. Pairing still expires after two minutes, and the power button still locks the vault.
- **Windows shows why a vault copy failed.** If the phone could not prepare the copy, it still sent a normal end of transfer. Windows then reported `snapshot read failed: failed to fill whole buffer`. The phone now sends its reason instead, and Windows shows "The phone could not send the vault copy: …". A joining Android phone shows the same reason. If the phone stops without a reason, Windows says so instead of reporting a read error.
- **The phone names its own failure.** When the phone cannot prepare the copy, its pairing screen shows the check that failed rather than a general "Pairing stopped" message. Messages from system errors give only the error type, so file paths and vault data are never shown.
- **Connection errors are specific.** "The other phone stopped offering pairing" means the phone that showed the QR locked or left its pairing screen. "This phone cannot reach the other phone" means the network blocked the connection, for example a guest network or a router that stops devices from connecting to each other.
- **Photo sync with Windows works in release builds.** In release builds 2.1.1–2.1.3, the phone's photo request used the renamed fields `a` and `b`, but Windows expects `hash` and `offset`. When a phone requested a photo, Windows rejected the request and the whole sync failed. Photos that Windows requested also failed on the phone. The field names are now kept in release builds. Both apps also accept the old names, so a phone that has not been updated yet can still request photos.

Existing signed membership, encrypted operations, progress tracking, photo integrity checks, and conflict handling are unchanged. The new failure report (`NUVFAIL1`) is sent only in place of a vault copy, inside the authenticated pairing channel.

## Update and check

1. Update every Android device with the [phone APK](../downloads/nuvori-v2.1.4-phone.apk?raw=true), and update both PCs with the Windows 2.1.4 installer from `card_app_windows/artifacts/`. The [phone AAB](../downloads/nuvori-v2.1.4-phone.aab?raw=true) is for Google Play upload. Update both apps before pairing; a 2.1.3 receiver cannot show the new failure reason.
2. Pair a second phone: on the managing phone, open **Devices**, show the QR and leave it on screen. On the new phone, scan it, enter the master password, and approve the matching code on both phones.
3. Pair Windows: show the QR on Windows and scan it with the managing phone. Only the managing phone can add a device. Approve the matching code on both. If pairing fails, read the message on both devices. Windows now repeats the phone's reason.
4. If a phone reports that it cannot reach the other device, put both on the same Wi-Fi network, not a guest network, and check that the router lets devices connect to each other. A PC on Ethernet must be routable from the phones' Wi-Fi. Allow Nuvori for the Windows Firewall profile used by that network.
5. After pairing, save a small note on Android and check that it arrives on Windows. Edit it on Windows and check that the update arrives. Add a photo to an entry and check that it arrives on the other device.

## Verification

- Android: 272 JVM tests passed, including new tests for the failure report, a transfer aborted after data was sent, and failure messages. The sync instrumentation suite was not rerun for this release.
- Windows: 142 Rust unit tests passed, including a new test for the phone's failure report and an empty transfer.
- Android release APK and AAB builds passed. Release lint reported zero errors and 77 warnings. The APK reports version 2.1.4, code 55, and is signed with the same certificate as 2.1.3 (SHA-256 `c88756b1…a6db79d9`), so it installs over earlier releases. The AAB signature verified. APK zip alignment and native library alignment meet 16 KB requirements. The release mapping shows that the photo request keeps its `hash` and `offset` field names.
- Windows MSI and setup EXE report version 2.1.4. The MSI contains the pairing helper and native browser host. Checksums are in `downloads/SHA256SUMS.txt` and `card_app_windows/artifacts/SHA256SUMS-2.1.4.txt`.

These checks ran on one Windows computer without a physical phone. They do not cover pairing between your physical phones, your router, or a second PC. The cause of the Windows vault copy failure you reported is still unknown. Version 2.1.4 shows that cause on both devices instead of a read error. If pairing still fails, send the exact messages from both screens.
