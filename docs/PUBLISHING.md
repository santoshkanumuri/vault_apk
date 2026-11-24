# Release builds and downloads

The phone release is version 2.3.3, version code 74, package `com.application.private_vault`. Use the existing release key to keep updates installable over earlier releases.

## Signing setup

Install JDK 17, Android SDK API 36, and Build Tools 36.0.0. Set the SDK path in `local.properties` as shown in the [README](../README.md#build-from-source).

The phone build reads signing properties from `NUVORI_SIGNING_PROPERTIES`, or defaults to `~/.private-vault-signing/signing.properties`. The file names `storeFile` and `keyAlias`. Keep signing files and passwords outside the repository. Without the signing file, Gradle produces unsigned release outputs.

The password lives in Windows Credential Manager, not in the file or on a command line (security review L-14). `scripts\create-release-signing.ps1` makes a new key and stores its password there. For an existing key whose password is still in `signing.properties`, run `scripts\create-release-signing.ps1 -MoveToCredentialManager` once: it stores the password and removes it from the file. Then run release builds through `scripts\release-build.ps1`, which hands the password to Gradle in its environment for that run only. A password still in the file keeps working.

## Build and verify

Run from the project root on Windows:

```powershell
.\scripts\release-build.ps1 :app:testDebugUnitTest :watchcommon:test :app:lintRelease :app:assembleRelease :app:bundleRelease
```

On macOS or Linux, use `sh gradlew` with the same tasks.

Build the watch companion separately. It uses version 2.1.2, code 360012:

For each new Google Play upload, update `versionCode` in `wear/build.gradle.kts` to an unused, higher number and rebuild. Codes up to 360011 were used by earlier releases, so this watch release uses 360012. `versionName` is the displayed label; the watch is 2.1.2 with the phone at 2.3.3. Renaming an APK or AAB does not change its embedded version. See [Android app versioning](https://developer.android.com/studio/publish/versioning).

```powershell
.\scripts\release-build.ps1 :watchcommon:test :wear:testDebugUnitTest :wear:lintRelease :wear:assembleRelease :wear:bundleRelease
```

Watch outputs are `wear/build/outputs/apk/release/wear-release.apk`, `wear/build/outputs/bundle/release/wear-release.aab`, and `wear/build/outputs/mapping/release/mapping.txt`. Verify their versions and signatures, and confirm the signer matches the phone release. Watch setup and device testing are described in the [companion guide](WATCH-COMPANION.md).

The watch keeps Fragment 1.8.2 to override the old dependency brought in by Play Services Wearable. Check the resolved release dependency before building future updates:

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

Copy the verified APK to `downloads/nuvori-v<version>-phone.apk`. Keep the AAB and the matching `mapping.txt` (as `nuvori-v<version>-phone-mapping.zip`) outside the repository, for example next to the signing key, because the repository is public and both undo R8's obfuscation (security review L-13). The mapping decodes crash reports after build outputs are cleaned; Play Console also gets it with the AAB.

Keep the watch APK as `downloads/nuvori-v2.1.2-watch.apk`, with its AAB and mapping in the same private place. Each mapping archive must match its own APK and AAB.

Only the current phone release is kept in `downloads/`; earlier packages stay in Git history. Keep the per-release checksum file `downloads/SHA256SUMS-<version>.txt` with them.

Refresh `downloads/SHA256SUMS.txt` to list only release files actually present:

```powershell
Get-ChildItem downloads -File | Where-Object { $_.Extension -eq '.apk' } | Sort-Object Name | ForEach-Object {
    $hash = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    "$hash  $($_.Name)"
} | Set-Content downloads/SHA256SUMS.txt -Encoding ascii
```

Update the README download links and release notes with the checks that actually ran. Only `downloads/` APKs are allowed by `.gitignore`; AABs, mapping files, build intermediates and signing files stay ignored.

Install the APK directly on Android 10 or newer. Upload the AAB through Google Play Console for store distribution. The AAB also embeds its R8 mapping. Building these files does not publish a Play release.
