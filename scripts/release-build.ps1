# Runs Gradle with the release signing password from Windows Credential Manager (security review
# L-14). The password reaches Gradle only through this process's environment, never a command line.
# Example: powershell -File scripts\release-build.ps1 :app:assembleRelease :app:bundleRelease
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'signing-credential.ps1')
$password = Get-NuvoriSigningPassword
if (-not $password) { throw "No signing password in Credential Manager. Run scripts\create-release-signing.ps1 (or -MoveToCredentialManager) first." }
$env:NUVORI_SIGNING_PASSWORD = $password
try {
    & (Join-Path $PSScriptRoot '..\gradlew.bat') @args --console=plain
    $code = $LASTEXITCODE
} finally {
    Remove-Item Env:NUVORI_SIGNING_PASSWORD -ErrorAction SilentlyContinue
}
exit $code
