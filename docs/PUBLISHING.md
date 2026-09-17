# Release signing and downloads

The README links to the signed APK in `downloads/`. Commit that folder along with the source before pushing. The relative download link will then work on GitHub without a separate Release. Keep only the current APK in this folder. Git history will still retain older files; for ongoing releases, consider GitHub Releases to keep binaries out of the source history and update the README link accordingly.

## Signing your own build

Gradle reads `${user.home}/.private-vault-signing/signing.properties`. Create that directory outside this repository and keep its contents private:

```properties
storeFile=C:/path/outside/repository/private-vault-release.jks
storePassword=YOUR_KEYSTORE_PASSWORD
keyAlias=YOUR_KEY_ALIAS
keyPassword=YOUR_KEY_PASSWORD
```

Use the same signing key for every update of your release. Back it up securely. A different key cannot update an already installed release. Without this configuration, `assembleRelease` produces an unsigned APK.

```powershell
.\gradlew.bat testDebugUnitTest connectedDebugAndroidTest lintDebug assembleRelease
```

The output is `app/build/outputs/apk/release/app-release.apk`. Verify it with Android SDK Build Tools' `apksigner verify` before publishing.

## Updating the download

1. Increase `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Build, test, and verify the signed APK. Test an upgrade with an existing dummy vault and restore a backup into another vault.
3. Copy it to `downloads/private-vault-VERSION.apk` and remove the previous download from the working tree.
4. Update `downloads/SHA256SUMS.txt` with the filename and SHA-256 hash. On PowerShell, use `Get-FileHash -Algorithm SHA256`.
5. Update the README download link/version and add release notes.
6. Review `git status` before committing. Never include signing files, vaults, backups, real account data, or local build logs.

Anyone building their own distribution should use their own signing key.
