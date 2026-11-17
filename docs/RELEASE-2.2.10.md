# Nuvori 2.2.10

Android version 2.2.10, code 68. Wear OS companion version 2.1.2, code 360012 (unchanged). Pairs with Windows 2.2.9 and browser extension 0.7.7.

## Quick copy tile (was "Vault codes")

- The Quick Settings tile is now called **Quick copy**. After you unlock it, four buttons switch between **Passwords**, **TOTP**, **Cards** and **Questions**. The tile remembers the last one.
- Each item has a copy button per field:
  - Passwords: Username, Password, and Code when a TOTP is linked to the login.
  - Cards: Number, Expiry, Name, CVV. The number is copied as digits only; the expiry as MM/YY.
  - Questions: Answer. TOTP: tap the code, as before.
- **Copy the other fields from your notifications.** After one copy, the picker closes and the other fields of that item stay in a notification for 3 minutes. Pull down the shade, tap Expiry, paste; tap CVV, paste. No second unlock. The shade closes after each tap.
  - The notification holds a random token only; values stay in app memory. It is hidden on the lock screen, its buttons need an unlocked phone, it never goes to a watch, and it makes no sound.
  - It ends after 3 minutes, when the screen turns off, when you swipe it away, or on Lock now.
  - It shows at most three buttons (an Android limit). A card with four fields leaves Name out; Name is in the picker.
  - In the picker, the CVV still needs a fingerprint if you unlocked over a minute ago. From the notification, the CVV works for the 3 minutes after you picked the card.
  - With Nuvori notifications off, each copy closes the picker as before, and the picker says how to turn them on.
- Search never matches passwords, answers, CVVs or more than a card's last four digits.

## Checks

- Unit tests: 452 passed, 0 failed (`:app:testDebugUnitTest`), plus 14 in `:watchcommon:test`. New: `QuickCopyTest` (8 tests): fields per type, linked code only when loaded, digits-only number and MM/YY expiry, three notification buttons, wrong or expired token refused, an old token never ends a newer session, search never matches secrets.
- Instrumented tests on an Android 16 (API 36) emulator: `VaultQuickAccessTest` (5, one new for cards and questions) and `VaultCodesTest` (2) passed.
- On the emulator: the notification showed expanded at the top with Number, Expiry and CVV; tapping Expiry copied a masked (sensitive) clip, showed "Expiry copied" and updated the notification; a screen-off removed it.
- Release lint: 0 errors.
- The signed phone APK reports 2.2.10, code 68, with the same signer certificate as 2.2.9 (SHA-256 `c88756b1…6db79d9`). It passed `zipalign -c -P 16`. All native libraries in the APK and AAB passed the 16 KB alignment check. The AAB signature verified with `jarsigner`. R8 kept `QuickCopyReceiver` under its manifest name.
- The release APK installed over 2.2.9 on the emulator and started without a crash.
- Not run on a real phone: the tile on a real payment or sign-in page, and the notification buttons behind a real screen lock.
