# Nuvori 2.3.3

Android version 2.3.3, code 74. Wear OS companion version 2.1.2, code 360012 (unchanged). Pairs with Windows 2.3.0 and browser extension 0.7.8.

Version 2.3.2 (code 73) was built but not published. 2.3.3 includes its changes.

## Website icons

Logins and passkeys show the logo of their website instead of a letter, for about 800 popular sites. The logos are built into the app, so nothing is downloaded and no service learns which sites you use. Sites without a built-in logo keep their letter. Turn it off in **Settings > Appearance > Website icons**.

The logos come from [Simple Icons](https://simpleicons.org) 16.36.0 (CC0 icons only). The list of sites is matched to logos by `scripts/build-site-icons.py` in the Windows repository.

## Stronger master password protection

Nuvori now times the key derivation (Argon2id, 64 MiB) on each phone to take about 1 second, instead of a fixed setting. A faster phone gets more protection. The new setting is saved at the next unlock with the master password.

**One-way change:** after that unlock, an older Nuvori (2.3.1 or earlier) can't open the vault on that phone. Google Play does not offer downgrades, so this affects only a manual install of an older APK.

New Google Drive backups and exported backups use the same timed setting (backup format `PVLT0002`). Older backups still restore.

## Passphrase generator

**Generate password** has options: a random password, or a passphrase of words from the EFF large word list (7,776 words), with your choice of separator, capital letters and a number.

## Previous passwords and Recently deleted

- Each login keeps its last 10 passwords on this phone, under **Previous passwords**.
- Deleted items stay in **Settings > Recently deleted** for 30 days, where you can restore them.
- Both include changes and deletes that arrive from your other devices. Both stay on this phone and are not synced. A restored item syncs as a new item.

## Most used logins and passkeys

Nuvori now learns which logins and passkeys you use on this phone and lists them first.

- Order: favorites first, then the items you use most, then by name. This applies to the keyboard suggestions, the account picker, the Quick copy tile and the passkey picker.
- When a site has two or more logins (or passkeys), the one you use most gets a small **Most used** badge. The keyboard strip shows it as "Most used · <name>".
- A fill, a copy, a keyboard pick and a passkey sign-in each count as one use. A code fill and a generated password do not count.
- Older uses count for less: the weight halves every 30 days, so a new habit takes over quickly.
- The record holds only item IDs, counts and times, never a username or password. It is encrypted with a key in the Android Keystore, stays on this phone, and is not synced or backed up.

## Passkey manager

The **Passkeys** page now shows each saved passkey and what happened to it.

- **Saved passkeys:** a list with search. Tap a passkey to see its site, account, creation date, the app that created it (with its icon), the last change, sign-in count, public key fingerprint, and shortened credential ID and user handle. The private key is never shown.
- **Passkey history:** a log of passkeys created, imported, synced from another device, restored, used to sign in, and deleted (here or on another device). Use **Changes only** to hide sign-ins. **Clear** removes the log; your passkeys stay saved.
- **Passkey requests:** the list of recent sign-in requests now keeps up to 100 entries after a restart, with app icons and dates, a "Not offered" filter and a **Clear** button. The list is encrypted on this phone and is not backed up.
- The history keeps at most 3,000 entries, and at most 500 of them sign-ins, so changes are never pushed out by sign-ins. It stays in the encrypted vault on this phone and is not synced or backed up.
- Passkeys saved before 2.3.2 show "Added before history", because the old version did not record when they came in.

The vault database moves to version 21 with two new tables (`passkey_history`, `passkey_origins`). The upgrade runs once, at the first unlock.

## Sync status on Home

Home no longer says your devices "haven't synced in 3 days" when they sync through Google Drive. The warning used to look only at direct device-to-device sync. Each device now also updates its Drive sync record at least once an hour, even when nothing changed, so the other devices can see it is active. When a device has not synced, Home suggests opening Nuvori on it.

## Checks

- `:app:lintRelease`, `:app:testDebugUnitTest` (497 tests, 0 failures), `:app:assembleRelease` and `:app:bundleRelease` passed.
- Instrumented tests on the emulator: 35 of 35 passed (VaultQuickAccessTest, PasskeyManagerUiTest, PasskeyHistoryStorageTest, ItemUsageStoreTest, VaultMigrationTest 9→21, SyncTransactionTest, LockedSyncExchangeTest).
- `SiteIconRenderTest` on the emulator: GitHub and Netflix draw their brand colors, and an unknown site keeps its letter.
- The APK reports version code 74 and version name 2.3.3, and is signed with the same certificate as 2.3.2 (SHA-256 `c88756b1…6db79d9`). `zipalign -c -P 16` passes, native libraries are 16 KB aligned, and the AAB signature verifies.

## Files

- [Phone APK](../downloads/nuvori-v2.3.3-phone.apk)
- [Phone AAB](../downloads/nuvori-v2.3.3-phone.aab) for Google Play upload
- [Phone R8 mapping](../downloads/nuvori-v2.3.3-phone-mapping.zip): upload it with the AAB, so crash reports can be read.
- The 2.3.1 and 2.3.2 mapping files stay in `downloads/` for crash reports from phones still on those versions.
