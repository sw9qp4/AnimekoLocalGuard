# AnimekoLocalGuard - generate a self-owned release signing keystore.
#
# Why this exists:
#   G0 requires a baseline with an independent applicationId AND our own signing key. The package
#   id part is already done, but the APKs built so far are signed with the local Android *debug*
#   key, which is not "our own" key in any meaningful sense.
#
#   No build-file change is needed: app/android/build.gradle.kts already creates a "release"
#   signing config when `signing_release_storeFileFromRoot` and friends resolve, and the project's
#   getProperty falls back to local.properties. So this script only has to create the keystore and
#   record the four properties.
#
# The keystore is written OUTSIDE version control (see .gitignore) and must be backed up by the
# user: losing it means future versions cannot be installed as an upgrade over an existing one.
#
# IMPORTANT: this file is pure ASCII on purpose. Windows PowerShell 5.1 reads a BOM-less .ps1 as
# the ANSI code page, so non-ASCII characters can break parsing.
#
# Usage:
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\make_release_keystore.ps1
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\make_release_keystore.ps1 -Force

[CmdletBinding()]
param(
    [switch]$Force
)

$ErrorActionPreference = 'Stop'

$RepoRoot = Split-Path -Parent $PSScriptRoot
$KeystoreDir = Join-Path $RepoRoot 'keystore'
$KeystorePath = Join-Path $KeystoreDir 'animekolocalguard-release.jks'
$PropertiesPath = Join-Path $RepoRoot 'local.properties'

# Locate keytool from the project's own JDK so the result does not depend on the machine PATH.
$jdk = Get-ChildItem -Path (Join-Path $RepoRoot '.tools\jdk21') -Directory -ErrorAction SilentlyContinue |
    Select-Object -First 1
if (-not $jdk) { Write-Error "No JDK under .tools\jdk21; cannot run keytool."; exit 2 }
$keytool = Join-Path $jdk.FullName 'bin\keytool.exe'
if (-not (Test-Path $keytool)) { Write-Error "keytool not found at $keytool"; exit 2 }

if ((Test-Path $KeystorePath) -and -not $Force) {
    Write-Host "Keystore already exists: $KeystorePath"
    Write-Host "Refusing to overwrite it: replacing a signing key breaks upgrades for anyone who"
    Write-Host "already installed a build signed with the old key. Pass -Force only if you are sure."
    exit 0
}

if (-not (Test-Path $KeystoreDir)) {
    New-Item -ItemType Directory -Path $KeystoreDir -Force | Out-Null
}

# A long random password. It is stored only in local.properties (gitignored) and printed once
# below so the user can put it in their password manager.
$bytes = New-Object byte[] 24
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$rng.GetBytes($bytes)
$password = [Convert]::ToBase64String($bytes).Replace('+', 'A').Replace('/', 'B').Replace('=', 'C')
$alias = 'animekolocalguard'

Write-Host "Generating a self-owned release signing key..."
Write-Host "  keystore : $KeystorePath"
Write-Host "  alias    : $alias"
Write-Host "  keytool  : $keytool"
Write-Host ""

# -dname keeps this non-interactive. The identity is intentionally explicit that this is a local
# test key, so nobody can mistake it for an official Animeko signing key.
$dname = 'CN=AnimekoLocalGuard Local Test Key, OU=local-only, O=AnimekoLocalGuard, L=-, ST=-, C=CN'

# keytool reports its progress on stderr. With $ErrorActionPreference = 'Stop' a native command's
# stderr becomes a terminating error, so the progress line would abort the script. Relax the
# preference locally around the call only.
function Invoke-Native([string]$exe, [string[]]$Arguments) {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & $exe @Arguments 2>&1 | ForEach-Object { $_.ToString() }
    } finally {
        $ErrorActionPreference = $previous
    }
}

$keytoolArgs = @(
    '-genkeypair',
    '-keystore', $KeystorePath,
    '-storetype', 'PKCS12',
    '-alias', $alias,
    '-keyalg', 'RSA',
    '-keysize', '4096',
    '-sigalg', 'SHA384withRSA',
    '-validity', '10950',
    '-storepass', $password,
    '-keypass', $password,
    '-dname', $dname
)
Invoke-Native $keytool $keytoolArgs | ForEach-Object { Write-Host $_ }

if (-not (Test-Path $KeystorePath)) { Write-Error "keytool did not produce a keystore."; exit 3 }

# --- record the four properties upstream expects -----------------------------------------------
if (-not (Test-Path $PropertiesPath)) {
    Write-Error "local.properties not found; it should already exist for this project."
    exit 4
}
$text = [System.IO.File]::ReadAllText($PropertiesPath)
$block = @(
    '',
    '# AnimekoLocalGuard: self-owned release signing key (keystore/ is gitignored).',
    '# Used by app/android/build.gradle.kts -> signingConfigs.release, via getProperty.',
    'signing_release_storeFileFromRoot=keystore/animekolocalguard-release.jks',
    "signing_release_storePassword=$password",
    "signing_release_keyAlias=$alias",
    "signing_release_keyPassword=$password"
) -join "`r`n"

if ($text -match '(?m)^\s*signing_release_storeFileFromRoot\s*=') {
    Write-Host ''
    Write-Host 'local.properties already contains signing_release_* entries; leaving them untouched.'
    Write-Host 'If they point at the old key, remove them and rerun with -Force.'
} else {
    [System.IO.File]::WriteAllText($PropertiesPath, $text.TrimEnd() + "`r`n" + $block + "`r`n",
        (New-Object System.Text.UTF8Encoding($false)))
    Write-Host ''
    Write-Host 'Appended signing_release_* properties to local.properties.'
}

# --- verify what was created, and prove it differs from the debug key --------------------------
Write-Host ''
Write-Host 'Certificate fingerprint of the new key:'
& $keytool -list -v -keystore $KeystorePath -storepass $password -alias $alias 2>&1 |
    Select-String -Pattern 'SHA256:|Alias name:|Valid from:' | ForEach-Object { Write-Host "  $($_.Line.Trim())" }

Write-Host ''
Write-Host 'For comparison, the local debug key currently used by the debug APK has SHA-256:'
Write-Host '  96:6F:BA:3C:39:02:7A:88:2B:EE:79:49:CB:34:22:E8:B9:32:87:47:A9:F1:A8:99:8F:85:D9:C3:59:58:51:C0'
Write-Host 'The two must differ; if they match, something is wrong.'

Write-Host ''
Write-Host 'IMPORTANT - back this up now:'
Write-Host "  * keystore : $KeystorePath"
Write-Host "  * alias    : $alias"
Write-Host "  * password : $password"
Write-Host '  Losing the keystore means you can never again publish an upgrade over an already'
Write-Host '  installed build signed with it; users would have to uninstall first.'
Write-Host ''
Write-Host 'To build a release APK with this key:'
Write-Host '  powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\build_android.ps1 -Task ":app:android:assembleRelease"'
