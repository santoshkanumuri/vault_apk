# Nuvori 2.3.1

Android version 2.3.1, code 72. Wear OS companion version 2.1.2, code 360012 (unchanged). Pairs with Windows 2.2.9 and browser extension 0.7.7.

Version 2.3.0 (code 71) went only to Google Play review. 2.3.1 replaces it.

## Passkeys on another device

You can now sign in on a computer with a passkey saved in Nuvori on your phone.

1. On the computer, choose to sign in with a passkey, then **iPhone, iPad, or Android device**.
2. Scan the QR code with the phone's camera.
3. Choose **Unlock Nuvori** and pick the passkey.

Google Play services runs this sign-in on the phone and asks Nuvori for the computer's website. Earlier versions refused it, because Play services is not a browser, and Android showed "No passkeys available".

- Nuvori trusts Play services only for passkey requests. Password and form autofill still accept only the listed browsers.
- Play services must be signed with one of Google's keys. Google's list names its earlier keys, and Play services signs with a key it rotated to in 2024. Nuvori accepts it when a listed key is in its signing history, which Android verifies. This is the same rule as Android's own check.
- The website that Play services reports must be a plain HTTPS address, and the passkey's site must match it.

## Google Play availability

Wi-Fi is no longer a required hardware feature. Google Play now offers Nuvori on devices without Wi-Fi, such as some tablets, Chromebooks and Android XR devices. Device sync also works over Ethernet, and on a device without Wi-Fi, sync no longer stops at the start.

## Checks

- Unit tests passed (`:app:testDebugUnitTest` and `:watchcommon:test`). `TrustedBrowsersTest` (14 tests) checks that:
  - Play services is accepted through its real signing history and refused for autofill;
  - a history without a listed key, another package, or a browser with a listed key only in its history is refused;
  - the Play services keys match Google's list in `docs/trusted-browsers-source.json`.
- Instrumented tests on an Android 16 (API 36) emulator with Play services 25.26: `PasskeyRelayTest` reads the installed Play services signing history and resolves a Play services request for `https://login.microsoft.com` to that website. `NativePasskeyOriginTest` and `PasskeyBrowserTest` also passed.
- Release lint: 0 errors.
- The signed phone APK reports 2.3.1, code 72, with the same signer certificate as 2.2.11 (SHA-256 `c88756b1…6db79d9`). Its only required feature is `android.hardware.faketouch`; camera, NFC and Wi-Fi are optional. It passed `zipalign -c -P 16`. All native libraries in the APK and AAB passed the 16 KB alignment check. The AAB signature verified with `jarsigner`.
- Not tested yet: the QR sign-in itself on a real phone and computer.
