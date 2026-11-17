# Nuvori 2.2.9

Android version 2.2.9, code 67. Wear OS companion version 2.1.2, code 360012 (unchanged). Pairs with Windows 2.2.9 and browser extension 0.7.7.

## Passkey mark on logins

- A login that also has a passkey for the same account shows a green fingerprint:
  - next to the title in the vault list;
  - at the end of the keyboard suggestion chip;
  - as "· Passkey" in the drop-down suggestion row, on keyboards without inline suggestions.
- Screen readers hear "Passkey saved" on those rows and chips.
- The same account means: the usernames are the same (case and outer spaces ignored), and the passkey's site (RP ID) covers the login's website or one of its linked sites. A passkey for `github.com` marks a login saved for `https://github.com/login` or `gist.github.com`, not one for `notgithub.com` or `http://github.com`.
- Windows 2.2.9 uses the same rule, so a login shows the mark on both devices.
- The mark is only a hint. It never changes what fills or what signs in. The keyboard suggestions read only each passkey's site and username, never its key.

## Autofill on more forms

Tested in Chrome 133 and Firefox 160 on rsolomakhin.github.io/autofill, autofill.me and roboform.com's test forms.

- **Account picker is the default** for "How logins appear". After an unlock, Chrome and Firefox leave the keyboard closed, so keyboard suggestions stayed hidden until the field was tapped again. The picker shows the logins for the site at once; one tap fills. People who chose keyboard suggestions keep them.
- **Username-first sign-ins** fill when the page also keeps a hidden copy of the account box, and when two account boxes are visible (the one you are in).
- **A login and a code in one form**: the login fills in the username and password fields, the code in the code field. Before, Nuvori offered nothing there.
- **Mixed forms** (an account, a card and an address in one form): each field offers its own kind. Before, every field offered the login, and the card was never offered next to a password. A card number beside a password with no expiry or security code (a bank sign-in) is still not treated as a payment.
- **Drop-downs**: state and country lists take the saved address ("TX" or "Texas", "United States", "US" or "USA"); a card-type list takes the card's network ("Visa", "Master Card", "Visa (Preferred)"). A list without a matching entry is left alone.
- The card picker shows the expiry as MM/YY however it was typed.


## Checks

- Unit tests: 444 passed, 0 failed (`:app:testDebugUnitTest`), plus `:watchcommon:test`. New: `PasskeyMarksTest` (3 tests): same account and covered site only, an RP ID that is a child or a public suffix never matches, no username or website gives no mark.
- Release lint: 0 errors.
- The signed phone APK reports 2.2.9, code 67, with the same signer certificate as 2.2.8 (SHA-256 `c88756b1…6db79d9`). It passed `zipalign -c -P 16`. All native libraries in the APK and AAB passed the 16 KB alignment check. The AAB signature verified with `jarsigner`.
- Not run on a real phone: the passkey mark in the vault list and in a keyboard's suggestion chip, and the autofill changes above.
