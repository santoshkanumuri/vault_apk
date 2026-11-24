# Creates the Android release key, or with -MoveToCredentialManager moves the password of an
# existing signing.properties into Windows Credential Manager (security review L-14). The password
# is never on a command line or in a file; Gradle gets it through scripts/release-build.ps1.
param([switch]$MoveToCredentialManager)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'signing-credential.ps1')
$signingDirectory = Join-Path $env:USERPROFILE '.private-vault-signing'
$keyStorePath = Join-Path $signingDirectory 'private-vault-release.jks'
$propertiesPath = Join-Path $signingDirectory 'signing.properties'

function Write-SigningProperties {
    $portablePath = $keyStorePath.Replace('\', '/')
    $properties = @(
        "storeFile=$portablePath"
        'keyAlias=private-vault'
        '# The password is in Windows Credential Manager. Build with scripts/release-build.ps1.'
    )
    [IO.File]::WriteAllLines($propertiesPath, $properties, [Text.UTF8Encoding]::new($false))
}

if ($MoveToCredentialManager) {
    if (-not (Test-Path -LiteralPath $propertiesPath)) { throw "No signing properties at $propertiesPath." }
    $lines = [IO.File]::ReadAllLines($propertiesPath)
    $store = ($lines | Where-Object { $_ -like 'storePassword=*' } | Select-Object -First 1)
    $key = ($lines | Where-Object { $_ -like 'keyPassword=*' } | Select-Object -First 1)
    if (-not $store) { Write-Output 'signing.properties holds no password. Nothing to move.'; return }
    $storePassword = $store.Substring('storePassword='.Length)
    if ($key -and $key.Substring('keyPassword='.Length) -ne $storePassword) {
        throw 'The store and key passwords differ. Move them by hand; this script expects one password.'
    }
    Set-NuvoriSigningPassword $storePassword
    if ((Get-NuvoriSigningPassword) -cne $storePassword) { throw 'Credential Manager did not keep the password. signing.properties was not changed.' }
    $kept = $lines | Where-Object { $_ -notlike 'storePassword=*' -and $_ -notlike 'keyPassword=*' }
    $kept += '# The password is in Windows Credential Manager. Build with scripts/release-build.ps1.'
    [IO.File]::WriteAllLines($propertiesPath, [string[]]$kept, [Text.UTF8Encoding]::new($false))
    Write-Output "Moved the signing password to Credential Manager ('$script:NuvoriSigningTarget') and removed it from $propertiesPath."
    return
}

if ((Test-Path -LiteralPath $keyStorePath) -or (Test-Path -LiteralPath $propertiesPath)) {
    throw "Release signing files already exist at $signingDirectory. They were not changed. Use -MoveToCredentialManager to take the password out of signing.properties."
}

New-Item -ItemType Directory -Path $signingDirectory -Force | Out-Null
icacls.exe $signingDirectory /inheritance:r /grant:r "$env:USERNAME`:(OI)(CI)F" | Out-Null

$secure = Read-Host 'New release key password (16 characters or more; leave empty to generate one)' -AsSecureString
$pointer = [Runtime.InteropServices.Marshal]::SecureStringToGlobalAllocUnicode($secure)
try { $signingPassword = [Runtime.InteropServices.Marshal]::PtrToStringUni($pointer) } finally { [Runtime.InteropServices.Marshal]::ZeroFreeGlobalAllocUnicode($pointer) }
if (-not $signingPassword) {
    $passwordBytes = [byte[]]::new(32)
    $randomSource = [Security.Cryptography.RandomNumberGenerator]::Create()
    $randomSource.GetBytes($passwordBytes)
    $randomSource.Dispose()
    $signingPassword = [Convert]::ToBase64String($passwordBytes)
    [Array]::Clear($passwordBytes, 0, $passwordBytes.Length)
} elseif ($signingPassword.Length -lt 16) {
    throw 'Use at least 16 characters.'
}

# keytool reads the password from the environment (-storepass:env), not from its command line.
$env:NUVORI_SIGNING_PASSWORD = $signingPassword
$keytoolArguments = @(
    '-genkeypair', '-v',
    '-keystore', $keyStorePath,
    '-storepass:env', 'NUVORI_SIGNING_PASSWORD',
    '-keypass:env', 'NUVORI_SIGNING_PASSWORD',
    '-alias', 'private-vault',
    '-keyalg', 'RSA',
    '-keysize', '3072',
    '-validity', '10000',
    '-dname', 'CN=PrivateVault,O=Local,C=US'
)
try {
    $process = Start-Process -FilePath 'keytool.exe' -ArgumentList $keytoolArguments -Wait -PassThru -NoNewWindow
} finally {
    Remove-Item Env:NUVORI_SIGNING_PASSWORD -ErrorAction SilentlyContinue
}
if ($process.ExitCode -ne 0) { throw "keytool failed with exit code $($process.ExitCode)" }

Set-NuvoriSigningPassword $signingPassword
Write-SigningProperties
Write-Output "Created release signing material in $signingDirectory. The password is in Windows Credential Manager as '$script:NuvoriSigningTarget'. Back up the key store and the password somewhere safe and offline."
