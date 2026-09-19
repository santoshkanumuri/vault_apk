# Private Vault 1.9.1

Version code: 27. Package: `com.application.private_vault`.

Add and edit screens now keep Back and Save at the top while fields scroll below. The controls remain reachable on short screens and with the keyboard open. Back appears on the left in entry details and the photo viewer. The quick-add list scrolls on small screens, and the main toolbar has less empty space. Bottom navigation uses shorter labels without repeating them to screen readers.

The Cards tab no longer shows search or sort controls. Cards retain their saved order. Home search still finds cards.

On short screens, autofill uses Android's regular suggestion instead of an inline keyboard suggestion. The unlock action stays at the top of the picker, and saved logins use shorter rows so they remain reachable with enlarged text.

This update does not change vault data or backup formats. Install it over v1.9.0 with the same signing key. Export an encrypted backup before updating and check your entries afterward.

Checks: unit tests, Android lint, 20 Android 16 emulator tests at a compact size with enlarged text, and a separate wide-editor check. The keyboard-open layout and native autofill flow passed. The release APK was checked for signing, native alignment, no Internet permission, and debugging disabled. Fingerprint and layout behavior on a physical S26 Plus still need a real-device check.
