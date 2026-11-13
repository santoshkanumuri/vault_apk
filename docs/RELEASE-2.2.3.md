# Nuvori 2.2.3

Android version code 61. Windows version 2.2.3. Browser extension version 0.7.2. Same features as [2.2.2](RELEASE-2.2.2.md), plus one change.

## Leave requests through Google Drive

- **A device that leaves:** with Google Drive sync on, the phone also uploads its signed leave request to Google Drive. It seals the request with the group's cloud key while leaving. The upload retries with each Drive check.
- **The managing phone:** it reads new leave files in its Drive checks. It accepts one only when:
  - the device's own signature is valid,
  - the key epoch is accepted,
  - the request is at most 30 days old,
  - the request was made during the device's current membership.
- **Then:** it signs the removal, exactly as for a leave request over Wi-Fi. The new device list goes to Google Drive in the same check.
- **Before:** a leave request reached the managing phone only over Wi-Fi.

## Verification

- 308 app JVM tests passed. New coverage: a Drive leave request is answered like one over Wi-Fi, and needs the device's own signature and current membership; the managing device answers a signed leave file, ignores a forged or misnamed one, and other devices adopt the removal from the drive; the leave file name and body match the shared values that Windows checks.
- Signed APK and AAB builds passed. The APK reports version 2.2.3, code 61, with the same signer certificate as 2.2.2 (SHA-1 `3702b3b5…bab3e62c`). All native libraries passed the 16 KB alignment check. The AAB signature verified with `jarsigner`.
- Not run: a real leave between devices through Google Drive.

## Download files

- [Phone APK](../downloads/nuvori-v2.2.3-phone.apk)
- [Phone AAB](../downloads/nuvori-v2.2.3-phone.aab) for Google Play upload
- [R8 mapping](../downloads/nuvori-v2.2.3-phone-mapping.zip)
- [Android checksums](../downloads/SHA256SUMS-2.2.3.txt)
