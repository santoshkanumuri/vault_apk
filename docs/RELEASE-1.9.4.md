# Private Vault 1.9.4

Version code: 30. Package: `com.application.private_vault`.

New installs open with four short introduction pages. They explain what the vault stores, how local protection works, and how to bring over an encrypted backup or a Chrome/Brave password CSV. Each page has Next and Back controls; the transition follows Android's animation setting. The Start page lets you choose a new vault, backup restore, or browser password import.

All three choices create a master password first. Backup restore then asks for the password used when that backup was made and shows a review before replacement. Browser import opens the CSV action and previews accounts before saving them. Browser CSV import covers passwords, not passkeys. The Start and navigation controls remain visible on short screens with enlarged text.

Existing vaults skip the introduction. The vault and backup formats are unchanged. Install this update over v1.9.3 with the same signing key.

Checks: 36 unit tests, lint, and 30 passing Android 16 emulator tests; one optional live-browser passkey test was skipped. The new UI tests cover Next/Back, the browser-import route, and the Start button at 320 × 420 dp with 1.8× text. The signed release APK has the same signing certificate as v1.9.3, passes 16 KB native and ZIP alignment checks, has no Internet permission, and is not debuggable. Physical S26 Plus verification is still needed.
