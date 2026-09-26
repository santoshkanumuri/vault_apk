# Nuvori 2.0.4

Phone version code 44. The Wear companion is unchanged. The phone APK and AAB are in `downloads/`.

- Added Autofill details profiles for name, email, phone and address fields. Profiles live in the encrypted vault and use the existing entry backup and device sync path.
- Added an opt-in, Android Keystore encrypted copy of profile details for suggestions without vault unlock. Each device updates its copy after it unlocks and applies changes. Turning the setting off deletes the local copy. Passwords and TOTP secrets are excluded.
- Profile suggestions fill fields with recognized Android hints or clear HTML field names. Generic numeric fields are ignored.
- One-time-code fields offer Nuvori's unlock action; the linked authenticator code is generated when selected in the picker.
- Improved password keyboard suggestions and two-step login save state. Tablet navigation labels sit under their icons.

Verification: profile field matching and encrypted-copy tests passed on Android emulators. A phone emulator filled email, phone, city and postal code from a profile through Gboard without vault unlock. The existing native password and TOTP Autofill flow passed after the profile change. Real Brave and Google sign-in save behavior, varied website address forms, and physical-device sync delivery still need device testing.
