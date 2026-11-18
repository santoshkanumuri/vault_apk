# Nuvori 2.2.11

Android version 2.2.11, code 69. Wear OS companion version 2.1.2, code 360012 (unchanged). Pairs with Windows 2.2.9 and browser extension 0.7.7.

This release cleans up notifications. Nuvori now shows a notification only when Android requires one or when you need it for Quick copy. The notification goes away when the job is done.

## Auto sync notifications

| Situation | 2.2.10 | 2.2.11 |
|---|---|---|
| Auto sync paused | "Device sync paused" with a Resume button | No notification. The Auto sync tile shows **Paused**. |
| Opening Nuvori or **Sync now** while paused | A sync notification appeared, then the paused notification came back, also after you swiped it away | No notification. The check runs once while Nuvori is open. |
| Auto sync on | Text changed at each sync step ("0 connected · 1 nearby", …) | One fixed notification, **Auto sync is on**, with a **Pause** button. Nuvori never updates it, so after you dismiss it, it stays dismissed. |

- Android requires one notification while a service runs in the background. That is why Auto sync on still has one. A status bar icon without a notification is not possible on Android.
- On Android 13 and later, **Android devices > Automatic sync > Hide Auto sync notification** opens Android's setting for this one notification type. Turn it off: sync keeps running, Android lists Nuvori under **Active apps** in Quick Settings, and the Auto sync tile still shows On or Paused.
- The notification type is now called **Auto sync** (was "Device sync"). It is silent, hidden on the lock screen, and never goes to a watch.
- After the update, Nuvori removes a "Device sync paused" notification that 2.2.10 left in the shade.

## Quick copy notification

- It now lasts **2 minutes** (was 3), with a countdown.
- It closes when you copy the last field it offers. Example: copy the card number in the picker, then Expiry and CVV from the notification. After the CVV, the notification closes and shows "CVV copied. All fields copied."
- It still ends on screen-off, swipe-away and Lock now.

## Checks

- Unit tests: 472 passed, 0 failed (`:app:testDebugUnitTest` plus 14 in `:watchcommon:test`). New: `SyncRunTest` (4 tests: a check while paused or with notifications off never runs in the foreground; Auto sync on does) and 2 more in `QuickCopyTest` (closes after the last offered field; 2-minute duration).
- Instrumented tests on an Android 16 (API 36) emulator, 18 passed: the new `NotificationHygieneTest` (3), `DeviceSyncUiTest`, `HelpUiTest`, `VaultQuickAccessTest`, `SettingsUiTest` and `VaultCodesTest`. `NotificationHygieneTest` reads the notifications Android holds. It checks that:
  - Quick copy times out after 2 minutes and is gone after the last field is copied;
  - a sync on opening Nuvori while paused posts no notification;
  - a leftover paused notification is removed.
- On the emulator, **Hide Auto sync notification** opened Android's settings page for the "Auto sync" notification type, with the new description.
- Release lint: 0 errors.
- The signed phone APK reports 2.2.11, code 69, with the same signer certificate as 2.2.10 (SHA-256 `c88756b1…6db79d9`). It passed `zipalign -c -P 16`. All native libraries in the APK and AAB passed the 16 KB alignment check. The AAB signature verified with `jarsigner`. R8 kept `LanSyncService`, `QuickCopyReceiver`, `VaultApplication` and both tile services under their manifest names.
- The release APK installed over 2.2.10 on the emulator and started without a crash.
- Not tested: the Auto sync notification while sync runs, and hiding it, because both need two paired devices. Also not tested on a real phone: Quick copy on a real payment or sign-in page.
