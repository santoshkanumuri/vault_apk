# Nuvori 2.1.0 preview

Phone version code 51. The signed phone APK and AAB are in `downloads/`. The Wear companion is unchanged.

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

## Verification

- 138 JVM unit tests passed. They cover the new form classifier (login, one-time-code, and profile fields, including unsafe pages that must stay rejected), possible-match ranking, username rules, and password import.
- Every app source file was type-checked against Compose Multiplatform 1.6.11 with AndroidX stand-ins. There were no new errors compared with 2.0.9.
- Before publishing, still run: the debug unit and instrumentation suites, vital lint, the signed release APK and AAB build, and autofill checks in Chrome, Brave, and native apps on a phone.
