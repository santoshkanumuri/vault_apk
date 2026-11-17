# Nuvori 2.2.8

Android version 2.2.8, code 66. Wear OS companion version 2.1.2, code 360012 (unchanged). Pairs with Windows 2.2.8 and browser extension 0.7.6. Versions 2.2.6 (code 64) and 2.2.7 (code 65) were preview builds and were not published.

## Autofill where Nuvori can't confirm the site

- Before, Nuvori offered nothing in these cases:
  - a browser that is not on its verified list;
  - a page that isn't HTTPS (a router's admin page at `http://192.168.1.1`, say);
  - a page in an app with frames or several sites.
- Now the login field shows **Unlock to fill a login** there too. The picker says "Nuvori can't confirm this page" and names the app that receives the login. You search for any login, and Nuvori fills it once after you confirm.
- These pages are never linked to a login and never offer to save, because what they claim to be can't be trusted later.
- Card fields work the same way, with the picker's warning that the app receives the card.
- Verified browsers and apps work as before: matched logins first, one tap to fill.

## Passkeys from Google Password Manager import

- Before, importing passkeys from Google Password Manager skipped every one with "use an unsupported key type or extension". Google exports a PRF secret (`hmacCredentials`) with its passkeys, and Nuvori refused any passkey with extension data.
- Now these passkeys import and sign in as usual. Nuvori does not keep the extension data (a PRF secret, a large blob, the payment flag). A few sites use PRF to unlock encrypted data, and those sites may ask for another way to unlock. The passkeys stay in Google Password Manager too.
- Only passkeys whose key is not ES256 (P-256) are still skipped.

## Passkeys in more apps

- Nuvori accepted only strictly formatted passkey requests. Now it also accepts padded base64url values (a trailing `=`), an empty account name (it shows the display name or the site instead), and padded credential IDs at sign-in, as Google's provider does.
- **Settings > Passkeys > Recent passkey requests** lists the last 10 apps and websites that asked Nuvori, and why Nuvori was not offered ("This browser isn't on Nuvori's list of verified browsers", "The site doesn't accept ES256 keys", and so on). An app that shows only other managers and never appears here did not ask Nuvori: it uses Google's older FIDO2 sign-in, which offers only Google Password Manager. The list is kept in memory only.

## Suggestions show what they fill

- Contact-detail suggestions in the keyboard and the Autofill menu now show the value the field receives, like the system's own Autofill. In an email field the main line is "alice@example.com"; below it are the entry's name and the other values it fills ("Home · Alice Smith · +1 555 0100").
- Before, the suggestion showed only kinds such as "Name · Email · Phone".
- After unlock, the details picker shows the values each entry fills, too.

## Card autofill

- **Fill a card like a password.** Payment forms in apps and supported browsers show **Unlock to fill a card** in the Autofill menu or keyboard suggestions. Unlock, then pick a card from **Fill a card**.
- **The picker shows each card as it looks in Nuvori:** its own color, a chip and its last four digits, plus a line such as "Visa •••• 1111 · 08/29". The full number never shows. A search box appears when you have more than 6 cards.
- **Only what the form asks for.** Nuvori fills:
  - the number, also across split 4-digit boxes;
  - the cardholder, or the first and last name in two fields;
  - the expiry as one field (MM/YY, MM/YYYY or MMYY, from the field's hint or length), or as month and year boxes or drop-downs.
- **The security code is opt-in.** It fills only while **Also fill the security code** is on. If Nuvori was unlocked more than a minute before you pick the card, it asks for your fingerprint first.
- **Careful field detection.**
  - Explicit purposes come first: HTML `autocomplete` values such as `cc-number` and `cc-exp`, and Android's card hints.
  - Then names, ids, placeholders and labels such as "card number", "expiry month" and "CVV".
  - A field with another known purpose (e-mail, password, postal code) is never a card field.
  - "Security code" and "verification code" count as a card code only when the field also names the card, so one-time codes still go to the code autofill.
  - Gift, loyalty, membership and similar card numbers are not treated as payment cards.
- **Browsers.** The card fields must share one https origin. A hosted payment frame counts. Nothing fills on http pages. A page shown inside an app fills for that app, and the picker warns that the app receives the card.
- **Turn it off** with **Offer to fill cards** under **Settings > Autofill and codes > Everyday details**. It is on by default. Contact details still show next to the card option.

## Sync when Nuvori opens

- Each time you open Nuvori, it checks paired devices on this Wi-Fi and Google Drive once. You no longer need to tap **Sync now**.
- It runs also while automatic sync is paused and while the vault is locked. Received changes wait encrypted and apply after you unlock.
- Opening it again within a minute does not start another check. A biometric prompt or a Google sign-in screen does not start one either.
- Finding no device nearby shows no warning.
- Turn it off with **Sync when Nuvori opens** under **Android devices > Automatic sync**. It is on by default.

## Any card color

- The fixed list of card colors is now a full picker: a live card preview, a saturation and brightness square, a hue bar, and an exact `#RRGGBB` field.
- **On your other cards** shows the colors you already use, for one-tap reuse.
- The color is stored in the same field as before, so it syncs with Windows and older versions show it.

## Checks

- 436 app JVM tests and 14 shared watch tests passed, 0 failed. New coverage:
  - the suggestion preview: the values in a fixed order, a name part shown as the whole name, the main line left out of the line below;
  - the "unverified" fallback: a non-HTTPS page and a framed WebView are offered only while the user is in a login field, never with a site address; new-password and mixed-factor forms stay refused; the card fallback needs a focused card field;
  - card field detection: explicit tokens, names, one-time code conflicts, gift and loyalty cards;
  - card forms: payment frames, http pages, an app's WebView, native apps, the focused form, drop-downs;
  - card values: expiry shapes, split number boxes, month and year drop-downs, the security code switch;
  - pending card requests, sync on open timing, and color math.
- `PasskeyCryptoTest` (3 tests, one new: padded challenge and user ID, an empty account name, a padded credential ID at sign-in, readable refusal reasons) and `NativePasskeyOriginTest` passed on an API 34 emulator.
- `CredentialTransferTest` (3 tests: a P-256 passkey, a passkey with a PRF secret as Google exports it, an RSA key still skipped, duplicate IDs refused) passed on an API 34 emulator.
- `CardPickerUiTest` (6 tests: last four digits only, tap to fill, the security code switch, light and dark screenshots) passed on an API 34 emulator.
- Release lint: 0 errors.
- The signed phone APK reports 2.2.8, code 66, with the same signer certificate as 2.2.5 (SHA-256 `c88756b1…6db79d9`). It passed `zipalign -c -P 16`. All native libraries in the APK and AAB passed the 16 KB alignment check. The AAB signature verified with `jarsigner`.
- Confirmed on a real phone (2.2.8):
  - importing passkeys from Google Password Manager;
  - Uber Eats offers Nuvori, signs in with a Nuvori passkey, and creates new passkeys in Nuvori.
- Not run on a real phone:
  - card autofill end to end;
  - the new contact-detail suggestion rows in a keyboard;
  - the Recent passkey requests list;
  - the "unverified" fallback.

  Before publishing, test in Chrome, in one app with a native card form, in one app with a WebView, in a browser that is not on the verified list, and on an `http://` page.

## Download files

- [Phone APK](../downloads/nuvori-v2.2.8-phone.apk)
- [Phone AAB](../downloads/nuvori-v2.2.8-phone.aab) for Google Play upload
- [Phone R8 mapping](../downloads/nuvori-v2.2.8-phone-mapping.zip)
- [Watch APK](../downloads/nuvori-v2.1.2-watch.apk)
- [Watch AAB](../downloads/nuvori-v2.1.2-watch.aab) for Google Play upload
- [Watch R8 mapping](../downloads/nuvori-v2.1.2-watch-mapping.zip)
- [Android checksums](../downloads/SHA256SUMS-2.2.8.txt)
