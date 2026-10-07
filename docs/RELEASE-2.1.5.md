# Nuvori 2.1.5

Android version code 56. Windows version 2.1.5.

## Sync fixes

- Android chooses the local interface that matches the peer's subnet. A hotspot peer no longer has to use the host phone's upstream Wi-Fi connection.
- The Android sync listener accepts connections across local interfaces, including the hotspot. It starts even without a Wi-Fi client network and reuses its saved port after a restart when that port is available.
- Discovery runs across available networks. A discovery error no longer closes the sync listener. Saved-address checks continue.
- Android and Windows allow two minutes to confirm matching codes. Android now reports confirmation expiry instead of leaving a dead attempt on screen. Scanning near the invitation's expiry no longer invalidates an already connected, confirmed attempt.
- Network failures during the password proof retain their network error. They no longer claim the passwords differ. Android also distinguishes a connection timeout from a stalled handshake.

## Verification

- 283 Android JVM tests passed. New coverage includes hotspot route selection, hotspot discovery without a Wi-Fi client network, excluding non-LAN interfaces, listener port reuse, timeout reporting, and cancellation.
- 15 Android 16 emulator tests passed, covering QR pairing, the initial vault copy, locked exchanges, six-device synchronization, and Windows interoperability.
- 142 Windows Rust tests passed. The separate interoperability test also passed with the Android emulator, exchanging later edits in both directions and switching which device initiated the connection.
- The two timeout regression tests failed before their fixes and passed afterward.

Physical hotspot behavior has not been verified with this build. Emulator and route-selection tests cannot establish what a particular phone or router permits. The original Wi-Fi still needs a network where the devices can reach each other.

## Check on the paired phones

1. Install the 2.1.5 phone APK on both phones as an update. Keep the existing vaults and pairing.
2. Leave the managing phone's hotspot on and connect the second phone to it. Open Nuvori on both phones and enable automatic sync.
3. Tap **Sync now**. Create a small note on the managing phone and check that it arrives on the second phone. Edit it on the second phone and check that the manager receives the edit.
4. Disconnect the second phone, make another edit, then reconnect. Check that the edit arrives without pairing again. Repeat with a photo.
5. Install the Windows 2.1.5 update and repeat the note edit in both directions while the PC is connected to the same reachable local network.

If a check fails, record the top sync status and the device-card message on both devices. Do not clear either vault to retry a network connection.
