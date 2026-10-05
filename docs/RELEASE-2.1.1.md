# Nuvori 2.1.1 preview

Phone version 2.1.1, code 52. Wear companion version 2.1.1, code 360011. Package `com.application.private_vault`; build both with the existing release key so they update 2.1.0 in place. Build steps are in [PUBLISHING.md](PUBLISHING.md).

## Home and navigation

- A new home screen puts the important things first: search, then anything that needs attention, then your favorites and recently opened items, live codes, categories, and quick actions.
- The attention strip appears only when something needs you: changes to review, a sync problem, changes waiting to apply, a device that hasn't synced in 3 days, a managing-role step, or a card that is expired or expiring soon. Otherwise one line says "Everything's in sync · 2 min ago".
- Quick access shows favorites and recently opened items. Tap to open; long-press to copy.
- Live codes shows up to 4 authenticator codes, favorites first, with a countdown ring. Tap a code to copy it.
- **Add** opens a type picker. **Sync now** opens Devices & sync when nothing is paired yet. **Scan or import** gathers QR and import options.
- The bottom bar is Home · Cards · Passwords · Codes · More. Tapping the current tab clears search, then the open folder, then scrolls to the top.
- Icons now come from one set shared with Nuvori for Windows, so the same item looks the same on both.

## Motion and feedback

- Tabs fade between each other; details, editors, and settings pages slide in and out. Reduced-motion settings are respected.
- Going back returns to the same place in the list, with the same sections expanded. On Android 13 and later, the back gesture previews the screen underneath.
- Tiles, cards, and rows respond to presses. The card wallet expands smoothly without redrawing every card.
- Notices keep their color while they wait in line and sit above the bottom bar. New notices cover locking, unlocking, sync results, theme and NFC changes, device removal, and each managing-role step. Removing a favorite can be undone.
- Each item keeps the same letter and color everywhere, including autofill and the codes picker. A login without a username shows "No username".

## Sync

- Interrupted transfers keep everything already received and resume where they stopped, without duplicates. Large transfers are much faster: the phone no longer re-reads the whole queue for every change it receives.
- A sync progress report could briefly move backward while changes were being applied, which made the other device stop. Fixed.
- Photos resume from the partial file after a dropped connection, instead of starting over.
- Edits made during a sync are sent in a quick follow-up pass.
- Deleting an item also deletes its photos on every device. Photo files nothing uses any more are cleaned up at unlock.
- The phone stays awake and on fast Wi-Fi only while an authenticated transfer runs, for up to 15 minutes.
- A connection that never authenticates is dropped after 15 seconds, so it cannot block real syncs.

## Windows

- Later edits and photos now sync with a paired Windows PC in both directions. Windows connects to the phone; the phone never dials the PC.
- Logins saved from the Windows browser extension without a username, logins with custom names, and photo cover choices sync correctly.
- When Windows reconnects several times to finish a large sync, the phone no longer interrupts it, so Windows doesn't report "busy".

## Devices and the managing role

- The managing device can remove any other device. The removed device's approval isn't needed, and it keeps its own copy of your items.
- A removed device finds out the next time it reaches any remaining device, even after being offline. It checks the signed removal record before acting, then leaves the group, keeps its items, and shows "This device was removed from the sync group". A fake message on the same Wi-Fi cannot make a device leave.
- **Remove this device from the group** lets a device leave on its own. It sends a signed request, and the managing device removes it automatically. If the managing device is offline, the request is retried for up to 30 days.
- The managing device cannot leave until it hands over the role. Its leave button explains this and links to **Transfer managing role**.
- Transferring the managing role is a 3-step flow (Offer → Accept → Complete), shown on every device, with Cancel at each step. Only the managing device can cancel a transfer.
- The devices list shows each device's platform, a "Managing device" badge, when it was last seen, and its status.

## Watch

- Watches with up to 8 codes open straight to the list. Larger lists keep the letter index and dial and add a Recent group.
- Tapping a code shows it full screen with a countdown ring that turns amber and red near the end, the next code in the last 10 seconds, and Copy code with haptic feedback.
- The watch shows when it last received codes ("Synced 5 min ago"). On the phone, **Settings > Watch codes** shows which watch is connected and when codes were last sent.
- The watch sync format is unchanged.

## Compatibility

- Phones on 2.1.0 keep syncing with 2.1.1 until a device is removed. After a removal, 2.1.0 phones already could not sync with the remaining devices; update every phone to 2.1.1 before removing a device.
- Device removal and leaving work with Nuvori for Windows builds that include the matching membership update.
- Shared test vectors (`app/src/test/resources/sync-vectors/membership-v1.json`) keep the phone and Windows byte-for-byte compatible.

## Known limitations

- Removing a device does not change the vault's encryption key. A removed device can no longer receive changes over the network, but could still read change data obtained some other way.
- A device being offered the managing role cannot decline it; cancel the transfer on the managing device.
- Device platform is inferred from the device name.

## Verification

- Built October 5, 2026 from `main` after merging the watch redesign and the phone refresh.
- 261 phone JVM tests and 8 shared watch tests passed, with no failures or skipped tests. New coverage includes resuming after a dropped connection (3,250 changes; photos resuming from a partial file), Windows-created entries and photos, orphan photo cleanup, conflict convergence in every arrival order, removal notices, signed leave requests, and the shared membership vectors.
- The instrumentation sources compile. They did not run because no Android device was connected.
- `:app:lintRelease` and `:wear:lintRelease` passed with no errors. The signed, minified phone and watch release APK and AAB builds passed.
- The release APKs identify `com.application.private_vault` 2.1.1, code 52 (phone) and code 360011 (watch). `apksigner verify` passed, and both signer certificates match the 2.1.0 phone APK (SHA-256 `c88756b1…6db79d9`).
- Before publishing, still test on physical devices: pairing and sync with Windows (edits, photos, a dropped connection), removing a device and leaving the group, a managing-role transfer, autofill in Chrome, Brave and native apps, and watch sync.
