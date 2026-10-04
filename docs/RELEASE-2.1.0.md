# Nuvori 2.1.0 preview

Phone version code 51. The signed phone APK and AAB are in `downloads/`. The Wear companion uses version 2.1.0, code 360010; signed watch downloads are now available too.

- [Install the phone APK](../downloads/nuvori-v2.1.0-phone.apk?raw=true)
- [Download the phone AAB for Google Play](../downloads/nuvori-v2.1.0-phone.aab?raw=true)
- [R8 mapping for crash reports](../downloads/nuvori-v2.1.0-phone-mapping.zip)
- [SHA-256 checksums](../downloads/SHA256SUMS.txt)

## Autofill

- Recognizes more sign-in forms. Apps that only name their fields (an ID such as `et_email` or a hint such as "Email or username") now get their username filled. As a last resort, the plain text field just before a lone password counts as the username. Visible-password and numeric PIN fields count as passwords.
- Pages with several forms, such as a header search box beside the login form, now fill the form that holds the password. When two forms both ask for a password, the one you are typing in wins. Iframes, non-HTTPS pages, mixed origins, and unverified browsers are still rejected.
- Signup and password-change pages are recognized from field names such as `password_confirmation` when they lack autocomplete hints. A plain password next to a field marked only as new is still left alone, because it may be a change form.
- One-time-code fields named `otp`, `2fa`, `mfa`, or `verification_code` are offered linked codes. A code field in the same form as a password is still refused.
- Autofill details fill separate first-name and last-name fields from the saved name, and repeated fields such as "confirm email". Suggestions say what they will fill, for example "Name · Email · Phone +2", without showing the values. Card fields and partial phone fields are never filled.
- The account picker shows the requesting app's icon and name or the website's host. When no login is linked yet, it lists **Possible matches** by website or app name and opens search right away. A suggestion still needs **Fill and link** confirmation, which names the website a login is saved for when it differs. Sites on shared hosts such as github.io or vercel.app are not treated as the same service. The link is bound to the exact website or the app's signing certificate.
- After the picker fills a login with a linked authenticator, the current code is copied as a sensitive clipboard item for the next screen. Turn it off in **Settings > Autofill and codes > Copy linked code after filling**.
- Unlocking in the picker is quicker. The fingerprint button comes first, the master password field can show what you typed, the keyboard's Done key unlocks, and a wrong password is marked on the field.
- Save and update prompts show the app or website, the username, a hidden password with **Show**, and an editable name. They say whether the password is already saved, differs from a saved login (**Update**), or is new. App sign-in screens without a username field can be saved; you type the account name. When Nuvori only guessed which box held the account, it asks you to confirm the name before saving. Server, workspace, and company boxes are never taken as the account. A lone numeric PIN screen does not ask to save. When the password is already saved, **Done** also completes Android Credential Manager save requests.
- Passkey requests show the website, account, and requesting app on one card. Sign-in lists each matching passkey as a row.
- Android's suggestion menu follows the light or dark system theme.

## Codes tile

- The picker remembers the Passwords or TOTP tab. Search has a clear button. Tapping a masked code card copies the current code. The countdown bar turns amber in the last 5 seconds and red in the last 2.
- Copying shows a short confirmation that names the field, never the value.

## Feedback and settings

- Saving, updating, and deleting entries, groups, folders, photos, and passkeys shows a colored confirmation. Copying a field shows a confirmation too. Problems stay on screen longer and can be dismissed. Error messages no longer show internal exception text.
- Settings are grouped into Security, Autofill and passkeys, Sync, Backup and data, App, and Support. Rows show their status: whether Nuvori is the Autofill service, paired devices, saved passkeys, watch, theme, and NFC.
- Security puts **Lock now** first. Backup, Passkeys, Watch codes, Appearance, and NFC pages use the same card layout, and destructive actions are red.

## Android devices

- The page opens with the sync status, the last check time, and **Sync now**. Conflicts and rejected changes come next, then your devices, then **Automatic sync** (now a switch) with its check interval, then pairing. **Stop sharing on this device** moves to a Danger zone at the bottom.
- Colors carry the same meaning everywhere: green is checked, blue is in progress, amber is waiting or paused, and red needs attention.
- Sync actions confirm what happened, such as "Automatic sync paused" or "Conflict resolved". The Auto sync tile shows On, Paused, or Set up.

## Windows pairing

- Copy the vault, including photos, to an unlocked Nuvori Windows vault through the encrypted pairing connection. Windows keeps its existing local items.
- Scan the managing phone's QR on the PC or use **Copy link**. When the PC cannot reach the phone, show a QR in Windows Sync and choose **Scan Windows QR** on the phone. Both devices must be reachable across their local networks.
- Enter the phone's master password in Windows and confirm the matching code on both devices before copying. Later edits do not sync with Windows yet. A Windows installer is outside this Android repository.

## Verification

- Built October 4, 2026 from the merged 2.1.0 code. Fixed the retry block's indentation in `AndroidPairing.kt` so release lint passes; behavior is unchanged.
- 219 JVM tests passed, with no failures or skipped tests. This includes form classification, possible-match ranking, username rules, password import, sync, Windows portable-backup framing, and the shared watch protocol.
- `:app:lintRelease` passed with zero errors and 72 warnings. The signed, minified release APK and AAB builds passed.
- Both archives identify package `com.application.private_vault`, version 2.1.0, code 51. APK and AAB signatures verified, and the signer certificate matches the 2.0.9 APK. All 576 AAB payload entries verified as signed. JDK `jarsigner` also reports self-signed certificate, timestamp, and ZIP streaming-order warnings.
- APK ZIP alignment and every native library's 16 KB ELF alignment passed for both archives. SHA-256 checksums cover the files present in `downloads/`, including the matching R8 mapping archive. Superseded 2.0.9 phone downloads were removed; the current phone and watch builds remain.
- Instrumentation tests did not run because no Android device was connected. Before publishing, test autofill in Chrome, Brave, and native apps, plus Android and Windows pairing on physical devices.

## Watch build

- Fixed the Google Play outdated SDK warning for `androidx.fragment:fragment:1.1.0`. Play Services Wearable brought in the old version; the watch now explicitly uses Fragment 1.8.2, matching the phone. The release dependency graph and the rebuilt AAB's dependency metadata both confirm 1.8.2.
- [Watch APK](../downloads/nuvori-v2.1.0-watch.apk?raw=true), [watch AAB](../downloads/nuvori-v2.1.0-watch.aab?raw=true), and [R8 mapping](../downloads/nuvori-v2.1.0-watch-mapping.zip) were built from the current checkout on October 4, 2026. Package `com.application.private_vault`, version 2.1.0, code 360010. The watch version code was increased from 360009 for the Fragment dependency fix.
- The signed, minified watch APK and AAB builds passed. Watch release lint passed with zero errors and 17 warnings. The three shared watch protocol tests passed; the watch module has no JVM unit tests.
- Both signatures verified and match the phone release certificate. All 84 watch AAB payload entries verified as signed. APK ZIP alignment passed. The watch archives contain no native libraries. JDK `jarsigner` reports the same self-signed certificate, timestamp, and ZIP streaming-order warnings as the phone bundle.
- The matching R8 mapping is archived and included in the AAB. Checksums cover all retained download files. Watch instrumentation tests and paired-device setup remain pending because no device was connected. See the [watch setup guide](WATCH-COMPANION.md).
