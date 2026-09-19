# AnimekoLocalGuard - device audit and on-device verification.
#
# Why this exists:
#   The second half of G1's completion criterion ("controlled before display") can only be
#   verified on a real device, while the project has hard constraints ("device is read-only",
#   "never modify or overwrite the phone's original Animeko"). Neither should depend on someone
#   remembering the rules at the moment of action, so the default mode here is READ-ONLY and
#   every write requires an explicit switch.
#
# Default (no switches): read-only audit. Nothing is installed, launched or changed.
#
# IMPORTANT: this file is deliberately pure ASCII. Windows PowerShell 5.1 reads a BOM-less
# .ps1 as the system ANSI code page (GBK on this machine), and a multi-byte character can then
# be mis-decoded into a quote character, breaking string literals. The Chinese explanation of
# the manual steps lives in docs/G1_DEVICE_CHECKLIST.md instead.
#
# Usage:
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\device_verify.ps1
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\device_verify.ps1 -Install
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\device_verify.ps1 -Install -Launch
#
# Switches:
#   -Install     install our own build (separate package id, coexists with the official app)
#   -Launch      after installing, start the app and check whether it crashed
#   -Apk <path>  which APK to install; defaults to the newest one under artifacts\
#
# Explicitly NOT done: no root, no remount, no uninstall, no data clearing, no changes to the
# official Animeko installation.

[CmdletBinding()]
param(
    [switch]$Install,
    [switch]$Launch,
    [string]$Apk = ''
)

$ErrorActionPreference = 'Stop'

$RepoRoot = Split-Path -Parent $PSScriptRoot
$OurPackage = 'me.him188.ani.localguard'
$UpstreamPackage = 'me.him188.ani'

$script:Report = New-Object System.Collections.Generic.List[string]
function Say([string]$text) {
    Write-Host $text
    $script:Report.Add($text) | Out-Null
}
function Section([string]$title) {
    Say ''
    Say ('=' * 74)
    Say $title
    Say ('=' * 74)
}

# ---------------------------------------------------------------------------
# 0. Locate adb
# ---------------------------------------------------------------------------
$adb = Get-ChildItem -Path (Join-Path $RepoRoot '.tools\android-sdk') -Filter 'adb.exe' -Recurse -ErrorAction SilentlyContinue |
    Select-Object -First 1
if (-not $adb) {
    $adb = Get-Command adb -ErrorAction SilentlyContinue
}
if (-not $adb) {
    Write-Error "adb not found. Expected .tools\android-sdk\platform-tools\adb.exe, or adb on PATH."
    exit 2
}
$Adb = $adb.FullName

function Adb([string[]]$AdbArgs) {
    # adb writes ordinary status information (e.g. "daemon not running; starting now") to stderr.
    # With $ErrorActionPreference = 'Stop' a native command's stderr is turned into a terminating
    # error, which would abort the whole audit on a completely harmless message. So the preference
    # is relaxed locally around the call only, and both streams are merged into the result.
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $out = & $Adb @AdbArgs 2>&1
        return ($out | ForEach-Object { $_.ToString() })
    } finally {
        $ErrorActionPreference = $previous
    }
}

Section "0. Device connection"
# `adb devices -l` also emits daemon-startup chatter on stderr, which the merged stream picks up.
# Only lines shaped like "<serial> <state>" count as devices, otherwise the chatter would be
# mistaken for a connected phone and every later command would fail with "device '*' not found".
$devices = Adb @('devices', '-l') |
    Where-Object { $_ -match '^\S+\s+(device|unauthorized|offline|bootloader|recovery|sideload)\b' }
if (-not $devices) {
    Say 'No device detected.'
    Say ''
    Say 'On the phone: Settings -> About phone -> tap "Build number" 7 times to enable'
    Say 'Developer options, then enable "USB debugging" and reconnect the cable.'
    Say 'This script does not change anything on the phone.'
    exit 3
}
$devices | ForEach-Object { Say $_ }

$first = $devices | Select-Object -First 1
$serial = ($first -split '\s+') | Select-Object -First 1
if ($first -match 'unauthorized') {
    Say ''
    Say 'Device is unauthorized: tap "Allow USB debugging" on the phone screen, then rerun.'
    exit 3
}
Say ''
Say "Using device: $serial"

function Sh([string]$cmd) { return (Adb @('-s', $serial, 'shell', $cmd)) }

# ---------------------------------------------------------------------------
# 1. Read-only device info
# ---------------------------------------------------------------------------
Section "1. Device information (read-only)"
$props = [ordered]@{
    'brand / model' = 'ro.product.brand'
    'device'        = 'ro.product.device'
    'android'       = 'ro.build.version.release'
    'sdk'           = 'ro.build.version.sdk'
    'primary abi'   = 'ro.product.cpu.abi'
    'build type'    = 'ro.build.type'
}
foreach ($k in $props.Keys) {
    $v = ((Sh "getprop $($props[$k])") -join '').Trim()
    Say ("{0,-14} {1}" -f $k, $v)
}

# Root detection uses objective markers only; no privilege escalation is attempted.
# A single `ls` is used on purpose: the build scripts here run under Windows PowerShell 5.1,
# where `&&` / `||` are not valid statement separators.
$suPaths = @('/system/bin/su', '/system/xbin/su', '/sbin/su')
$suFound = @()
foreach ($p in $suPaths) {
    $r = (Sh "ls $p") -join ''
    if ($r -notmatch 'No such file|Permission denied|not found|Unknown') { $suFound += $p }
}
$magisk = ((Sh "pm list packages magisk") -join '').Trim()
Say ''
if ($suFound.Count -eq 0 -and [string]::IsNullOrWhiteSpace($magisk)) {
    Say 'root check: no su binary and no Magisk package found (read-only check, no escalation tried)'
} else {
    Say "root check: su=$($suFound -join ',') magisk=$magisk"
}

# ---------------------------------------------------------------------------
# 2. Existing Animeko on the phone (read-only; resolves assumption A1)
# ---------------------------------------------------------------------------
Section "2. Existing Animeko on the phone (read-only)"
foreach ($pkg in @($UpstreamPackage, $OurPackage)) {
    $installed = (Sh "pm list packages $pkg") -join ''
    if ($installed -notmatch [regex]::Escape($pkg)) {
        Say "$pkg : not installed"
        continue
    }
    Say "$pkg : installed"
    $dump = Sh "dumpsys package $pkg"
    $verName = ($dump | Select-String -Pattern 'versionName=(\S+)' | Select-Object -First 1).Matches.Groups[1].Value
    $verCode = ($dump | Select-String -Pattern 'versionCode=(\d+)' | Select-Object -First 1).Matches.Groups[1].Value
    Say ("    versionName = {0}" -f $verName)
    Say ("    versionCode = {0}" -f $verCode)

    # Signature fingerprint: read the installed APK back and hash it, instead of trusting any
    # published information about the official build.
    $apkPathLine = (Sh "pm path $pkg") | Select-String -Pattern '^package:' | Select-Object -First 1
    if ($apkPathLine) {
        $remote = $apkPathLine.Line.Replace('package:', '').Trim()
        Say "    installed   = $remote"
        $local = Join-Path $env:TEMP ("alg_device_{0}.apk" -f ($pkg -replace '\.', '_'))
        $pull = Adb @('-s', $serial, 'pull', $remote, $local)
        if (Test-Path $local) {
            $h = (Get-FileHash $local -Algorithm SHA256).Hash.ToLower()
            $size = (Get-Item $local).Length
            Say ("    APK SHA-256 = {0}  ({1:N0} bytes)" -f $h, $size)
            Say "    (copy pulled from the device for hashing; read-only, device untouched)"
            Remove-Item $local -Force -ErrorAction SilentlyContinue
        } else {
            Say "    could not read the APK ($($pull -join ' ')) - fingerprint not checked"
        }
    }
}
Say ''
Say 'Note: assumption A1 ("phone Animeko == main@28ec14ac") holds only if versionCode is 50406.'
Say 'Otherwise A1 must be marked false in STATE.json and the integration evidence re-evaluated.'

# ---------------------------------------------------------------------------
# 3. Optional install of our own build
# ---------------------------------------------------------------------------
if ($Install) {
    Section "3. Install our own build (separate package id, coexists with the official app)"
    if ([string]::IsNullOrWhiteSpace($Apk)) {
        $candidate = Get-ChildItem -Path (Join-Path $RepoRoot 'artifacts') -Filter '*.apk' -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if (-not $candidate) { Write-Error "No APK under artifacts\; build first."; exit 4 }
        $Apk = $candidate.FullName
    }
    if (-not (Test-Path $Apk)) { Write-Error "APK does not exist: $Apk"; exit 4 }
    $apkHash = (Get-FileHash $Apk -Algorithm SHA256).Hash.ToLower()
    Say "APK      : $Apk"
    Say "SHA-256  : $apkHash"
    Say ''
    Say 'Safety properties, enforced by package id AND signature:'
    Say "  * package id is $OurPackage, not the official $UpstreamPackage, so it cannot replace it"
    Say '  * the signing key is a local debug key, so the system would also refuse to overwrite'
    Say '    an app signed with a different key'
    Say '  * installing removes no user data; to revert, simply uninstall this package'
    Say ''
    $r = Adb @('-s', $serial, 'install', '-r', $Apk)
    $r | ForEach-Object { Say $_ }
    if (($r -join ' ') -notmatch 'Success') {
        Say ''
        Say 'Install did not succeed. If this is INSTALL_FAILED_UPDATE_INCOMPATIBLE, a package'
        Say 'with the same id but a different signature already exists - that is outside the'
        Say 'expected situation; report it rather than uninstalling anything.'
        exit 5
    }
}

# ---------------------------------------------------------------------------
# 4. Optional launch and crash check
# ---------------------------------------------------------------------------
if ($Launch) {
    Section "4. Launch and crash check"
    Adb @('-s', $serial, 'logcat', '-c') | Out-Null
    $start = Adb @('-s', $serial, 'shell', 'monkey', '-p', $OurPackage, '-c', 'android.intent.category.LAUNCHER', '1')
    $start | ForEach-Object { Say $_ }
    Start-Sleep -Seconds 12
    $crash = Adb @('-s', $serial, 'logcat', '-d', '-b', 'crash')
    $pidText = ((Sh "pidof $OurPackage") -join '').Trim()
    Say ''
    if ([string]::IsNullOrWhiteSpace($pidText)) {
        Say 'Process is not alive - it may have crashed.'
    } else {
        Say "Process alive: pid=$pidText"
    }
    if ($crash -and (($crash -join "`n") -match [regex]::Escape($OurPackage))) {
        Say 'Crash log entries mentioning our package:'
        $crash | Where-Object { $_ -match [regex]::Escape($OurPackage) } |
            Select-Object -First 30 | ForEach-Object { Say "    $_" }
    } else {
        Say 'No crash log entries mentioning our package.'
    }
    Say ''
    Say 'Now complete the manual checklist in docs/G1_DEVICE_CHECKLIST.md:'
    Say '  playback page -> danmaku settings -> the local-guard group'
    Say '  1) the master switch must default to OFF'
    Say '  2) the status line must say the prototype has no model installed; it must never'
    Say '     claim the guard is active'
    Say '  3) with the switch ON, danmaku must still display normally (the APK ships no story'
    Say '     pack, so nothing should be blocked)'
    Say '  4) cycling the three tiers must not break the status line or playback'
    Say '  5) killing and reopening the app must preserve the switch and tier'
}

# ---------------------------------------------------------------------------
# 5. Report
# ---------------------------------------------------------------------------
Section "5. Report"
$outDir = Join-Path $RepoRoot 'docs\evidence'
if (-not (Test-Path $outDir)) { New-Item -ItemType Directory -Path $outDir -Force | Out-Null }
$stamp = Get-Date -Format 'yyyy-MM-dd_HHmmss'
$outFile = Join-Path $outDir "device_verify_$stamp.txt"
$mode = if ($Install) { 'install+audit' } else { 'read-only audit' }
if ($Launch) { $mode = $mode + '+launch' }
$header = @(
    "AnimekoLocalGuard device report  $stamp",
    "mode: $mode",
    ''
)
[System.IO.File]::WriteAllText($outFile, (($header + $script:Report) -join "`r`n"), (New-Object System.Text.UTF8Encoding($false)))
Say "written: $outFile"
Say ''
Say 'This script writes nothing to the device by default. It does not root, remount, uninstall'
Say 'or clear any data, and it does not modify the phone''s original Animeko.'
