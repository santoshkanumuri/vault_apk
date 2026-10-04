# Release builds and downloads

The phone release is version 2.1.0, version code 51, package `com.application.private_vault`. Use the existing release key to keep updates installable over earlier releases.

## Signing setup

Install JDK 17, Android SDK API 36, and Build Tools 36.0.0. Set the SDK path in `local.properties` as shown in the [README](../README.md#build-from-source).

The phone build reads signing properties from `NUVORI_SIGNING_PROPERTIES`, or defaults to `~/.private-vault-signing/signing.properties`. The file must contain `storeFile`, `storePassword`, `keyAlias`, and `keyPassword`. Keep signing files and passwords outside the repository. Without the signing file, Gradle produces unsigned release outputs.

## Build and verify

Run from the project root on Windows:

```powershell
.\gradlew.bat :app:testDebugUnitTest :watchcommon:test :app:lintRelease :app:assembleRelease :app:bundleRelease --console=plain
```

On macOS or Linux, use `sh gradlew` with the same tasks.

Build the watch companion separately. It uses version 2.1.0, code 360010:

For each new Google Play upload, update `versionCode` in `wear/build.gradle.kts` to an unused, higher number and rebuild. Codes 360008 and 360009 were already uploaded, so this release uses 360010. `versionName` is the displayed label; this release uses 2.1.0 to match the phone. Renaming an APK or AAB does not change its embedded version. See [Android app versioning](https://developer.android.com/studio/publish/versioning).

```powershell
.\gradlew.bat :watchcommon:test :wear:testDebugUnitTest :wear:lintRelease :wear:assembleRelease :wear:bundleRelease --console=plain
```

Watch outputs are `wear/build/outputs/apk/release/wear-release.apk`, `wear/build/outputs/bundle/release/wear-release.aab`, and `wear/build/outputs/mapping/release/mapping.txt`. Verify their versions and signatures, and confirm the signer matches the phone release. Watch setup and device testing are described in the [companion guide](WATCH-COMPANION.md).

Watch code 360010 updates the old Fragment dependency brought in by Play Services Wearable to 1.8.2. Check the resolved release dependency before building future updates:

```powershell
.\gradlew.bat :wear:dependencyInsight --dependency androidx.fragment:fragment --configuration releaseRuntimeClasspath
```

The dependency report should select `androidx.fragment:fragment:1.8.2`. The AAB's `BUNDLE-METADATA/com.android.tools.build.libraries/dependencies.pb` was also checked for this release and identifies 1.8.2.

Signed outputs:

- APK: `app/build/outputs/apk/release/app-release.apk`
- AAB: `app/build/outputs/bundle/release/app-release.aab`
- R8 mapping: `app/build/outputs/mapping/release/mapping.txt`

Verify the APK with Build Tools `apksigner verify --verbose --print-certs` and `zipalign -c -P 16 -v 4`. Compare its signer certificate with the previous release APK. Verify the AAB signature with JDK `jarsigner -verify`, and check both archives with `python scripts/check-native-alignment.py <archive>` for native 16 KB alignment.

Run `:app:connectedDebugAndroidTest` with a test device connected. Check autofill in Chrome, Brave, and native apps, plus Android and Windows pairing on physical devices before publishing.

## Keep release files

Copy the verified outputs to `downloads/nuvori-v2.1.0-phone.apk` and `downloads/nuvori-v2.1.0-phone.aab`. Archive the matching `mapping.txt` as `downloads/nuvori-v2.1.0-phone-mapping.zip` to decode crash reports after build outputs are cleaned. Keep the current phone and watch APKs and AABs in `downloads/` and remove superseded versions.

Keep the watch outputs as `downloads/nuvori-v2.1.0-watch.apk`, `downloads/nuvori-v2.1.0-watch.aab`, and `downloads/nuvori-v2.1.0-watch-mapping.zip`. Each mapping archive must match its own APK and AAB.

Refresh `downloads/SHA256SUMS.txt` to list only release files actually present:

```powershell
Get-ChildItem downloads -File | Where-Object { $_.Extension -in '.apk', '.aab', '.zip' } | Sort-Object Name | ForEach-Object {
    $hash = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    "$hash  $($_.Name)"
} | Set-Content downloads/SHA256SUMS.txt -Encoding ascii
```

Update the README download links and release notes with the checks that actually ran. `downloads/` APKs and AABs are allowed by `.gitignore`; build intermediates and signing files stay ignored.

Install the APK directly on Android 10 or newer. Upload the AAB through Google Play Console for store distribution. The AAB also embeds its R8 mapping. Building these files does not publish a Play release.
