# Writes the cross-app vector enrollment-realistic-v1.json: the exact enrollment snapshot v4 the phone
# sends to a Windows PC after a realistic history (RealisticEnrollmentFixtureTest, real app code).
#
#   scripts/sync-vectors/enrollment_realistic.ps1 [path\to\card_app_windows]
#
# Output: app/src/test/resources/sync-vectors/enrollment-realistic-v1.json, and with a Windows path
# also src-tauri/fixtures/enrollment-realistic-v1.json there (the same bytes). Both apps test it.
# Never edit the JSON by hand: change the test and run this again. Keys and signatures are random,
# so each run writes a new file; both apps must pass with any of them.
#
# Runs on an Android emulator only (ANDROID_SERIAL, default emulator-5554), never on a real phone.
param([string]$Windows)
$ErrorActionPreference = 'Stop'
$android = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$serial = if ($env:ANDROID_SERIAL) { $env:ANDROID_SERIAL } else { 'emulator-5554' }
if ($serial -notlike 'emulator-*') { throw "Run this on an emulator only, not on $serial." }
$env:ANDROID_SERIAL = $serial
$package = 'com.application.private_vault.debug'
$name = 'enrollment-realistic-v1.json'

Push-Location $android
try {
    # install* keeps the app installed (connected*Test would uninstall it with the file).
    & .\gradlew.bat :app:installDebug :app:installDebugAndroidTest --console=plain
    if ($LASTEXITCODE) { throw 'The build failed.' }
} finally { Pop-Location }

$result = & $adb -s $serial shell am instrument -w -e class com.privatevault.app.sync.RealisticEnrollmentFixtureTest `
    "$package.test/androidx.test.runner.AndroidJUnitRunner"
$result | Write-Output
if (-not ($result -match '^OK \(1 test\)')) { throw 'RealisticEnrollmentFixtureTest failed.' }

$out = Join-Path $android "app\src\test\resources\sync-vectors\$name"
& $adb -s $serial pull "/sdcard/Android/data/$package/files/$name" $out
if ($LASTEXITCODE) { throw 'Could not copy the vector from the emulator.' }
Write-Output "wrote $out"
if ($Windows) {
    $target = Join-Path (Resolve-Path $Windows).Path "src-tauri\fixtures\$name"
    Copy-Item -LiteralPath $out -Destination $target -Force
    Write-Output "wrote $target"
}
