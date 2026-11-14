# Nuvori 2.2.4

Android version 2.2.4, code 62. Wear OS companion version 2.1.2, code 360012. Pairs with Windows 2.2.4 and browser extension 0.7.3.

## Autofill

- **Search in the account picker.** A search box sits at the top of the picker. Linked logins rank first.
- **Same site, one tap.** In Chrome and Brave, the picker also lists logins saved for another page of the same site. Nuvori uses the bundled Public Suffix List, so `a.github.io` and `b.github.io` stay separate. One tap fills such a login once, without a link. Keyboard suggestions still need an exact link.
- **Fill once or Fill and link.** For any other login, you confirm the destination first, then pick one of the two.
- **Code after login.** After Nuvori fills a login with a linked authenticator, the next code screen in the same app or site shows that login under **Just signed in** for 3 minutes. Unlock and tap **Fill code**. Nuvori keeps only the login id and the destination in memory, never the code.
- **Clipboard copy is now off by default.** Turn it on under Autofill and codes if you want the old behavior.
- **Login forms inside frames** fill for the frame's own HTTPS origin, as in Chrome's password manager. A form that spans two origins, or sits under a non-HTTPS page, is left alone.
- **Embedded browsers** (a sign-in page inside an app) still fill only for that app's package and signing identity. The picker shows the site the app says it displays and lists logins for that site first.
- **Contact details while locked.** Address and contact forms show **Unlock to fill your details**.
- **More browsers.** Website autofill and passkeys accept the stable release certificates of 13 browsers: Chrome, Brave, Edge, Firefox, Firefox Focus and Klar, Samsung Internet, Vivaldi, Opera, Opera Mini, DuckDuckGo, Naver Whale and Yandex. Only Chrome and Brave are device-tested.
- **Code tile.** With **Suggest codes for the previous app** on, the tile suggests codes for the last app that asked Nuvori to fill in the past 5 minutes. No Usage access is needed.

## Watch

- Each **Connect watch and sync codes** or **Sync codes now** sends the watch a new random 32-byte key. The phone stores it encrypted under the vault key, outside backups. **Remove codes from watch** deletes it.
- A watch paired before this release keeps working with its old key until the next **Sync codes now**.
- The watch follows the vault's accent color. The background stays black.

## Colors

- Pick one of 16 accents and one of 8 backgrounds under **Settings > Appearance**. The default is the same green as before.
- All 256 combinations pass a contrast check in light and dark mode.
- The choice syncs over Wi-Fi and Google Drive to the Windows app, and from there to the browser extension. Light or dark mode stays per device.
- **Update every device first.** A device sends a color change only after every other active device runs 2.2.4 or newer and has synced once over Wi-Fi. Until then the change stays on that device.
- Encrypted backups carry the colors. Older versions ignore them.

## Verification

- 387 app JVM tests and 14 shared watch tests passed, 0 failed. New coverage: picker search and same-site matching (Public Suffix List), code after login, frame origins and embedded-site hints, locked contact fill, trusted browser certificates, watch pairing keys, appearance sync rules against the shared vector file `appearance-v1.json` (the same file Windows checks), and readable contrast for every accent and background.
- Release lint passed for the phone and the watch.
- The palette contrast check passed for all 256 combinations (16 accents x 8 backgrounds x light and dark).
- Signed builds passed. The phone APK reports 2.2.4, code 62. The watch APK reports 2.1.2, code 360012. Both use the same signer certificate as 2.2.3 (SHA-256 `c88756b1…6db79d9`). Both APKs passed `zipalign -c -P 16`. All native libraries in the phone APK and AAB passed the 16 KB alignment check. Both AAB signatures verified with `jarsigner`.
- Not run: device tests of autofill in browsers other than Chrome and Brave, color sync between two real devices, and the watch color on a real watch.

## Download files

- [Phone APK](../downloads/nuvori-v2.2.4-phone.apk)
- [Phone AAB](../downloads/nuvori-v2.2.4-phone.aab) for Google Play upload
- [Phone R8 mapping](../downloads/nuvori-v2.2.4-phone-mapping.zip)
- [Watch APK](../downloads/nuvori-v2.1.2-watch.apk)
- [Watch AAB](../downloads/nuvori-v2.1.2-watch.aab) for Google Play upload
- [Watch R8 mapping](../downloads/nuvori-v2.1.2-watch-mapping.zip)
- [Android checksums](../downloads/SHA256SUMS-2.2.4.txt)
