# Nuvori 2.1.3

Phone version 2.1.3 uses version code 54. Windows is also 2.1.3. The watch stays at 2.1.1. A sync group supports six mobile devices, including the managing phone, and two Windows PCs.

## Sync corrections

- Android selects physical Wi-Fi for pairing and sync. A VPN that reports Wi-Fi as its underlying transport can no longer supply the pairing address or sync route.
- Android tries all usable private addresses returned by discovery, instead of keeping only the first. This lets it reach a Windows PC whose advertisement also includes virtual adapter addresses. A successful address moves to the front of the candidate list.
- Both apps try saved, authenticated routes before discovery results. Windows also saves the managing phone's route when the initial enrollment copy commits. This provides a route when multicast discovery is unavailable.
- Both apps send an explicit busy reply when another exchange holds the sync lock. They retry after a short randomized delay without treating the reply as a broken connection or increasing the failure delay.
- Windows counts failure once per group check. Failed address candidates no longer inflate the count and push retries into the five-minute delay prematurely. A busy reply remains visible even if a later candidate is unreachable.
- Windows records the check time even when discovery finds no routes. Its status distinguishes a busy device from an unreachable device.

These changes preserve the existing signed membership, encrypted operations, progress tracking, photo integrity checks, and conflict handling.

## Update and check

1. Update every Android device with the [phone APK](../downloads/nuvori-v2.1.3-phone.apk?raw=true), and update both PCs with the Windows 2.1.3 installer from `card_app_windows/artifacts/`. The [phone AAB](../downloads/nuvori-v2.1.3-phone.aab?raw=true) is for Google Play upload.
2. Keep automatic sync enabled and the Windows vault unlocked. Existing pairs should use **Sync now**. The initial copy already on Windows does not need another enrollment.
3. A PC on Ethernet and phones on Wi-Fi can exchange changes when the local network routes traffic between them. Allow Nuvori for the Windows Firewall profile used by that network.
4. Save a small note on Android and check that it arrives on Windows. Edit it on Windows and check that the update arrives on Android. For new Android devices, keep both pairing screens open through matching-code approval and the full copy.

## Verification

- Android: 268 JVM tests and 40 sync instrumentation tests passed. The instrumentation suite checks actual Android-to-Android pairing and initial copy, a busy response followed by successful sync, later edits, six-device relay, photos, membership, interrupted transactions, and database migrations.
- Android/Windows: a separate interoperability test passed on each platform. It uses the actual Android and Rust sync implementations, the packaged J-PAKE helper, real TCP connections through ADB, signed membership, Android Room/Keystore storage, and a Windows SQLCipher vault. Both connection roles and subsequent edits pass.
- Windows: 141 Rust unit tests and three additional pairing/network tests passed. The network tests check multicast discovery, one failure per group check, busy replies without failure backoff, and continuing to another endpoint after an offline device.
- Windows extension: 91 tests passed. TypeScript and Vite production builds passed.
- Android release APK and AAB builds passed. Release lint reported zero errors and 77 warnings. Signatures verified with the existing release certificate. The AAB contains version 2.1.3, code 54, and the matching crash mapping. APK zip alignment and native library alignment meet 16 KB requirements. The signed APK installed and launched on Android 16 with 16 KB memory pages.
- Windows MSI and setup EXE include version 2.1.3 and the pairing helper. Packages, checksums, and verification reports are retained in `build/release-2.1.3-verification/` and each repository's release directory.

The tests use one Windows computer and one Android emulator with isolated vaults. They do not verify your physical phones, a second PC, or your router's Ethernet-to-Wi-Fi and client-to-client routes. A VPN route-selection defect was reproduced in tests; whether a VPN caused your reported failure is unknown.
