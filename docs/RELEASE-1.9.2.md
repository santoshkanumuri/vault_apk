# Private Vault 1.9.2

Version code: 28. Package: `com.application.private_vault`.

Entry details now use a shared bottom action dock. The collapsed dock stays above Android's navigation area and shows **Pull up to edit**. Swipe it up or tap the prompt to reach Add photo, Camera, Edit, Duplicate, and Delete. This keeps the controls reachable on short screens and with enlarged text.

Chrome and Brave CSV imports now consolidate repeated rows by website and username. The review screen identifies new accounts, exact matches, and saved accounts whose imported password differs. For password changes, choose whether to keep the saved passwords or replace only those password fields. Replacements preserve names, notes, folders, photos, app authorizations, and linked authenticator codes.

Native Android apps with clear username and password fields can now trigger Android's Save/Update prompt after sign-in. Saving still requires unlocking Private Vault and confirming its review screen. New logins are bound to the requesting app's package and signing certificate. Password updates preserve the rest of the saved entry.

This update does not change vault data or backup formats. Install it over v1.9.1 with the same signing key. Export an encrypted backup before updating and check your entries afterward.

Checks: 36 unit tests and 21 Android 16 emulator tests at 840 x 1100 pixels with 1.8 font scale. The suite covers the action dock, native-app Save/Update, import conflicts and rollback, encrypted backup transfer, autofill boundaries, passkeys, TOTP, and photo storage. The release APK was checked for signing, native alignment, no Internet permission, and debugging disabled. Fingerprint and layout behavior on a physical S26 Plus still need a real-device check.
