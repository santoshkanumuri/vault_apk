# Nuvori 2.1.2

Phone version 2.1.2, code 53. Windows version 2.1.2. Install both updates before adding devices. The existing Wear companion stays at 2.1.1 and does not occupy a mobile sync slot.

The group supports six mobile devices, including the managing phone, and two Windows PCs. Pair each new device through the managing Android device. Both PCs can then exchange changes directly, and devices can relay another member's signed changes.

## Changes

- Windows discovers and advertises its sync listener on the local network. Android can initiate a connection to Windows. A saved IP address is optional.
- Windows checks discovered and previously authenticated peers. An offline device does not stop a reachable device from receiving changes. Results report how many devices were checked and how many still need to connect.
- Windows saves an endpoint only after the peer proves its signed group membership and device identity. Discovery does not grant access to the vault.
- Android discovery uses the Wi-Fi network selected for its sync sockets on Android 13 and later.
- Windows pairing QR codes include its local IPv4 addresses. Android tries each candidate instead of relying on the PC's default Internet route, which may point at a VPN or virtual adapter.
- Windows refreshes its vault lists and open photo list when queued changes apply. Android schedules another exchange after applying received changes so peers learn the applied progress sooner.
- Signed membership and enrollment accept eight active members. The managing Android device enforces separate limits of six mobile devices and two Windows devices.

## Install and connect

1. Install the [phone APK](../downloads/nuvori-v2.1.2-phone.apk?raw=true) and the Windows 2.1.2 installer from `card_app_windows/artifacts/`. The [phone AAB](../downloads/nuvori-v2.1.2-phone.aab?raw=true) is for Google Play upload.
2. Put the devices on a local network where they can reach one another. A PC can use Ethernet while the phone uses Wi-Fi on the same LAN. Allow Nuvori through Windows Firewall on private networks when prompted.
3. Keep the Windows vault unlocked and enable automatic sync on both apps. Existing pairs can use Sync now without entering an IP address.
4. Add the second PC and other phones from the managing Android device. If the phone must initiate pairing, generate the Windows QR and scan it on the managing phone. Keep both apps open until matching-code confirmation and the vault copy finish.

Changes made offline remain on their authoring device until a peer connects. Concurrent edits remain available for conflict review. A watch exchanges authenticator snapshots with its paired phone through the Wear Data Layer.

## Verification

- Android: 264 JVM tests and 36 sync instrumentation tests passed. The instrumentation tests include reverse pairing address validation, six-device relay, photos, membership, recovery, and interrupted transactions.
- Windows: 139 Rust tests passed. Three additional tests passed with the packaged pairing helper and a live local network interface. They check pairing in both roles, multicast discovery and listener removal, and sync continuing after an offline endpoint. The Rust suite also checks two Windows vaults exchanging edits, photos, and relayed Android changes in an eight-member group.
- Windows extension: all 91 tests passed. TypeScript and Vite production builds passed.
- Android release APK and AAB builds passed. Release lint reported zero errors and 76 warnings. APK and AAB signatures verified, and the APK certificate matches 2.1.1. The AAB contains version 2.1.2, code 53, and the matching crash mapping. APK zip alignment and all native libraries meet 16 KB alignment. The signed APK installed and launched on the Android 16 emulator.
- Windows MSI and setup EXE builds passed. Release artifacts and SHA-256 checksums are saved in each repository. Android verification reports are in `build/release-2.1.2-verification/`.

Physical testing across two separate Windows computers and Android phones remains necessary. The automated tests use isolated Windows vaults, real sockets and multicast on one Windows computer, and multiple Android vaults on one Android 16 emulator. They cannot establish whether a particular router isolates clients or whether another computer's firewall allows Nuvori.
