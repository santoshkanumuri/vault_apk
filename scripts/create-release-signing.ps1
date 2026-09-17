$ErrorActionPreference = 'Stop'
$signingDirectory = Join-Path $env:USERPROFILE '.private-vault-signing'
$keyStorePath = Join-Path $signingDirectory 'private-vault-release.jks'
$propertiesPath = Join-Path $signingDirectory 'signing.properties'

if ((Test-Path -LiteralPath $keyStorePath) -or (Test-Path -LiteralPath $propertiesPath)) {
    throw "Release signing files already exist at $signingDirectory. They were not changed."
}

New-Item -ItemType Directory -Path $signingDirectory -Force | Out-Null
$passwordBytes = [byte[]]::new(32)
$randomSource = [Security.Cryptography.RandomNumberGenerator]::Create()
$randomSource.GetBytes($passwordBytes)
$randomSource.Dispose()
$signingPassword = [Convert]::ToBase64String($passwordBytes)
$passwordBytes.Clear()

$keytoolArguments = @(
    '-genkeypair', '-v',
    '-keystore', $keyStorePath,
    '-storepass', $signingPassword,
    '-keypass', $signingPassword,
    '-alias', 'private-vault',
    '-keyalg', 'RSA',
    '-keysize', '3072',
    '-validity', '10000',
    '-dname', 'CN=PrivateVault,O=Local,C=US'
)
$process = Start-Process -FilePath 'keytool.exe' -ArgumentList $keytoolArguments -Wait -PassThru -NoNewWindow
if ($process.ExitCode -ne 0) { throw "keytool failed with exit code $($process.ExitCode)" }

$portablePath = $keyStorePath.Replace('\', '/')
$properties = @(
    "storeFile=$portablePath"
    "storePassword=$signingPassword"
    'keyAlias=private-vault'
    "keyPassword=$signingPassword"
)
[IO.File]::WriteAllLines($propertiesPath, $properties, [Text.UTF8Encoding]::new($false))

icacls.exe $signingDirectory /inheritance:r /grant:r "$env:USERNAME`:(OI)(CI)F" | Out-Null
Write-Output "Created release signing material in $signingDirectory"
