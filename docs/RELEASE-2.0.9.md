# Nuvori 2.0.9 preview

Phone version code 50. The signed phone APK and AAB are in `downloads/`. The Wear companion is unchanged.

- Fixed third-device pairing after management moves to another device and back. Enrollment now numbers the new member from the signed membership history, which includes management handoffs.
- Kept pairing actions and feedback in one card near the top of Android devices. The QR, code comparison, vault copy, result, and retry actions follow the same place on each phone.
- Gave the encrypted vault copy a longer per-read timeout after both phones confirm the code. The master password remains required on both phones because pairing proves that the passwords match. Fingerprint unlock can open the vault but cannot replace that proof.

Verification: the debug unit suite, 36 sync instrumentation tests, and focused UI tests passed on the phone emulator. The third-device test covers first enrollment, an edit from the second device, management transfer away and back, and enrollment of a third device. The signed, minified release APK and AAB and vital lint passed. Package version, signatures, and checksums were checked.

Physical three-device pairing, interrupted vault copies, and longer background Wi-Fi runs still need testing before this preview can be treated as a complete sync release.
