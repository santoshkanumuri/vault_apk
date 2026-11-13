# Nuvori 2.2.2

Android version code 60. Windows version 2.2.2. Browser extension version 0.7.2.

## Choose how often the phone checks Google Drive

- **Settings > Devices & sync**, Google Drive card: new choice **Check Google Drive every**: 15 s (default), 1 min, 5 min or **Sync now only**.
- The checks run while Nuvori is open and unlocked. Before, the phone checked every minute.
- A new choice takes effect at once.
- With **Sync now only**, the phone checks Google Drive when you open or unlock Nuvori and when you tap **Sync now**.
- With every choice, the phone uploads its own changes right after each change.
- Wi-Fi sync keeps its own **Check every** choice (30 s, 1 min, 5 min or 15 min).

## Verification

- 304 app JVM tests passed.
- Signed APK and AAB builds passed. The APK reports version 2.2.2, code 60, with the same signer certificate as 2.2.1 (SHA-1 `3702b3b5…bab3e62c`). All native libraries passed the 16 KB alignment check. The AAB signature verified with `jarsigner`.
- Not run: the new interval choice on a phone screen, and its timing against a real Google Drive.

## Download files

Replaced by [2.2.3](RELEASE-2.2.3.md).
