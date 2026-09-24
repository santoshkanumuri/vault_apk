# Nuvori 2.0.0 preview

Phone version code 40. Wear OS version code 360008. This build is a test preview for paired devices. Keep a separate encrypted backup until the sync release checklist in `sync-steps.md` is complete.

## What changed

- Up to four Android devices can pair and exchange supported vault changes over the same Wi-Fi. Each device keeps its own master password. A receiving device must start with an empty vault.
- Entries, authenticator data, groups, passkeys, and photos use signed change records. Photo ciphertext can resume from a saved offset after a dropped connection and is checked against its full SHA-256 hash before it is accepted. A stale offset longer than the sender's file now resets for the next attempt.
- The Android devices page groups pairing, sync, paired device help, and conflict review. Related actions share a row. Conflict details show both reported versions before a choice; secrets stay hidden.
- Automatic sync checks for paired devices about every 30 seconds, 1 minute, 5 minutes, or 15 minutes while its foreground service is running. These are retry intervals, not delivery guarantees. Android requires the service notification; manual Sync now is available.
- The Wear companion remains a separate code-only sync feature. It does not become a full vault peer.

## Reliability and security limits

Photo changes wait for complete verified bytes before applying. Interrupted transfers retain encrypted partial data and retry on a later connection. A locked receiver can save encrypted changes but applies them only after unlock. Keep both devices on the same Wi-Fi until the receiving device shows the photo and its applied progress advances. A full or damaged local disk can still interrupt a transfer; the source device and an encrypted backup remain important.

This preview does not yet support secure removal of a paired device with future-key rotation. Do not use it to replace a lost or untrusted device. Enrollment recovery after a disconnect at the final acknowledgement, large imports, full conflict combinations, bounded history and photo-blob cleanup, and storage-pressure handling also need work. Repeated sync can increase private app storage. These are release blockers, not hidden behind a successful build.

Physical testing by the user covered two Android devices exchanging edits and photos. The required three-device, tablet, process-death, disk-full, and device-removal matrix is still open. A release build or emulator test cannot prove those cases.

Verification for this build: phone and Wear unit suites passed; the focused phone LAN sync instrumentation suite passed four tests on the available Wear emulator, including interrupted-photo recovery and direct third-device exchange. The Wear UI suite passed four tests on that emulator. Both minified release APKs and AABs built and passed signature checks. The watch release APK launched on the emulator. Phone layout tests require a phone emulator or physical phone; the available emulator is a watch and cannot verify those layouts.

For phone and watch communication, install both from Google Play or install APKs signed with the same certificate. A locally signed phone and Play-signed watch may not communicate through Wear Data Layer.
