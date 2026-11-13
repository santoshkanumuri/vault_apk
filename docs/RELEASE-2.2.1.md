# Nuvori 2.2.1

Android version code 59. Windows version 2.2.1. Browser extension version 0.7.2. Same features as [2.2.0](RELEASE-2.2.0.md).

## Fix

- **Google Drive sign-in errors.** When Google's sign-in screen closed without success, the phone always said "Google sign-in was canceled". Google closes that screen the same way when the sign-in fails, for example when Google does not recognize this copy of the app. The phone now reads Google's answer in every case:
  - A real cancel still says "Google sign-in was canceled. Nothing changed."
  - A copy of the app that no Google OAuth client matches says "Google doesn't recognize this copy of Nuvori (Google code 10)".
  - Other failures show their Google code.
- The phone's log (tag `NuvoriDrive`) records the Google status code of a failed sign-in. It never records the token or the account.

## For the developer: which certificate Google sees

Google matches the phone app by its application ID and its signing certificate. Each way of installing needs its own Android OAuth client:

| Installed from | SHA-1 to register |
| --- | --- |
| The APK in `downloads/` | `37:02:B3:B5:C2:FD:FB:5A:7A:62:89:AE:AB:E7:F8:FD:BA:B3:E6:2C` |
| Google Play | The **App signing key** certificate in Play Console (not the upload key) |

## Verification

- 304 app JVM tests passed.
- Signed APK and AAB builds passed. The APK reports version 2.2.1, code 59, with the same signer certificate as 2.2.0 (SHA-1 `3702b3b5…bab3e62c`). All native libraries passed the 16 KB alignment check. The AAB signature verified with `jarsigner`.
- Not run: a Google sign-in on a phone with this build.

## Download files

Replaced by [2.2.2](RELEASE-2.2.2.md).
