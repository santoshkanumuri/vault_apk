# Wear OS companion

The phone and watch apps share the package name `com.application.private_vault` and the release signing certificate. Watch version 2.1.0, code 360010, is available as a signed [APK](../downloads/nuvori-v2.1.0-watch.apk?raw=true) and [AAB](../downloads/nuvori-v2.1.0-watch.aab?raw=true). [Checksums](../downloads/SHA256SUMS.txt) and the [R8 mapping](../downloads/nuvori-v2.1.0-watch-mapping.zip) are retained with the release.

Build the watch separately:

```powershell
.\gradlew.bat :watchcommon:test :wear:testDebugUnitTest :wear:lintRelease :wear:assembleRelease :wear:bundleRelease
```

## Installing matching builds

Install both apps from Google Play when testing a Play Store release. Play App Signing can use a different certificate from the local APK signing key. A manually installed phone APK can see the connected watch but cannot exchange Data Layer messages with a watch app signed with a different certificate. Repeated retries and removing the watch screen lock will not fix a signing mismatch.

For local testing, install phone and watch APKs built with the same signing key. Keep the debug package suffix consistent too. The upload certificate on an AAB is not necessarily the certificate Google Play uses for the installed app.

This mixed-install failure was reproduced by the user and resolved by installing the phone app from Google Play too. The existing Data Layer transport worked once both installations matched.

This watch build sets the setup message color explicitly for the dark screen, checks for queued snapshots when the app opens, and acknowledges snapshots after saving them. The phone distinguishes a missing watch screen lock from an unanswered request and reports whether initial delivery was confirmed or queued.

Upload the phone AAB from `app/build/outputs/bundle/release/` to the mobile track. In the same Play Console app, add Wear OS under **Test and release > Advanced settings > Form factors**, provide a watch screenshot, and upload the watch AAB from `wear/build/outputs/bundle/release/` to a Wear OS test track. Keep version codes unique across both modules. The watch app is marked non-standalone because it needs the phone for setup, then generates codes offline. Test on a paired watch before publishing.

On the phone, unlock Nuvori and open **Settings > Watch codes > Connect watch and sync codes**. The watch needs a screen lock and Nuvori installed. Nuvori sends an encrypted full snapshot of authenticator accounts through the Wear OS Data Layer. Later edits and deletions replace that snapshot when the devices connect; no periodic polling is needed. A disconnected watch keeps its last list. The Data Layer may use Google's encrypted relay when Bluetooth is unavailable.

The watch starts with occupied first letters and a count for each. Tap a letter or **All** to see codes. A rotary dial moves through the index; pausing on a letter for two seconds opens it. In the code list, the dial scrolls normally. Tap a code to copy it. The pause action is disabled when touch exploration is on, so screen reader users open letters by tapping.

**Remove codes from watch** sends an empty snapshot. A disconnected watch retains its current codes until it reconnects. If a watch is lost, also revoke the affected TOTP setup keys with each account provider; remote removal cannot reach a disconnected watch.

## Build verification

Watch code 360010 fixes the outdated Fragment SDK warning on code 360009. The watch explicitly uses `androidx.fragment:fragment:1.8.2` to upgrade the transitive 1.1.0 dependency from Play Services Wearable. Both the release dependency graph and AAB dependency metadata confirm 1.8.2. Upload the rebuilt watch AAB as a new release; the earlier upload still contains the old dependency.

The October 4, 2026 build passed release lint with zero errors and 17 warnings. The three shared protocol tests passed; the watch module has no JVM unit tests. APK and AAB signatures match the phone download, all 84 AAB payload entries verified as signed, and APK ZIP alignment passed. No native libraries are included. The mapping archive matches the mapping embedded in the AAB.

No device was connected, so watch instrumentation tests and paired-device setup remain pending. Run `:wear:connectedDebugAndroidTest` on a test watch, then check initial code delivery, offline code generation, and removal after reconnecting before publishing.
