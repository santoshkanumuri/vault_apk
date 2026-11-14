# Nuvori 2.2.5

Android version 2.2.5, code 63. Wear OS companion version 2.1.2, code 360012 (unchanged). Pairs with Windows 2.2.4 and browser extension 0.7.3.

## Google Drive backup

- **Back up to your own Google Drive.** Turn it on under **Settings > Backup and import > Google Drive backup**. Choose every hour, day, week or month. It works without a sync group, so a single phone is covered too.
- **One password.** You type your master password once when you turn it on. Each backup is a normal encrypted `.pvault` file that opens with that password. Later backups need no password.
- **Runs while unlocked.** The vault can be read only while it is unlocked. Nuvori checks every 5 minutes while it is open. If the phone stays locked, the backup runs at the next unlock.
- **Two copies per phone.** Each backup is a new file. After it uploads, Nuvori deletes this phone's older backups and keeps the newest two. A failed backup never deletes a good one. Other phones' backups are never touched.
- **Restore on a new phone.** On first start, choose **Restore a backup**, then **Find backups in Google Drive**. Sign in with the old phone's Google account, pick a backup by date and size, and enter the old master password. You review the contents before anything is replaced.
- **Not part of sync.** A restored vault gets a new vault ID, so it does not join any sync group. Sync on Android and Windows ignores the backup files (`nvbak1.<backup ID>.<time>`).
- **Master password change.** New backups use the new password at once, and a backup runs right away. `.pvault` files you saved yourself keep their original password.
- **Turning it off** stops new backups. The backups in your Drive stay until you delete Nuvori's hidden app data in the Google Drive settings.
- Uses the same `drive.appdata` permission as Google Drive sync. Nuvori still cannot see your other Drive files.

## Other changes

- The encrypted backup code moved into one shared module. The `.pvault` format is unchanged; files from earlier versions open as before.
- The privacy policy describes Google Drive backup.

## Verification

- 399 app JVM tests and 14 shared watch tests passed, 0 failed. New coverage: one backup key for many backups, a wrong password and a changed file, the backup key file bound to the vault key, backup names, the schedule and retry wait, keeping two backups per phone, a failed upload or write that keeps the old backups, sync ignoring backup files, and a file made by the 2.2.4 export code.
- The on-device backup test (`AuthenticatorBackupTest`, export and restore through the encrypted database) passed on an API 34 emulator.
- Release lint: 0 errors.
- The signed phone APK reports 2.2.5, code 63, with the same signer certificate as 2.2.4 (SHA-256 `c88756b1…6db79d9`). It passed `zipalign -c -P 16`. All native libraries in the APK and AAB passed the 16 KB alignment check. The AAB signature verified with `jarsigner`.
- Not run: a backup to and a restore from a real Google Drive account, and a visual check of the new Settings card. Test both on a real phone before publishing.

## Download files

- [Phone APK](../downloads/nuvori-v2.2.5-phone.apk)
- [Phone AAB](../downloads/nuvori-v2.2.5-phone.aab) for Google Play upload
- [Phone R8 mapping](../downloads/nuvori-v2.2.5-phone-mapping.zip)
- [Watch APK](../downloads/nuvori-v2.1.2-watch.apk)
- [Watch AAB](../downloads/nuvori-v2.1.2-watch.aab) for Google Play upload
- [Watch R8 mapping](../downloads/nuvori-v2.1.2-watch-mapping.zip)
- [Android checksums](../downloads/SHA256SUMS-2.2.5.txt)
