# AnimekoLocalGuard - tools/build_android.ps1
#
# Reproducible Android build for the independent test package.
#
# STATUS: this procedure produced a successful build on 2026-09-18:
#   BUILD SUCCESSFUL in 11m 33s / 1106 actionable tasks
#   output: android-default-universal-debug.apk
#   applicationId: me.him188.ani.localguard
#   evidence: docs/evidence/build_success_g0.txt
#
# WHY THIS SCRIPT EXISTS (not just `gradlew assembleDebug`):
#   This machine needs several project-local settings that are NOT upstream defaults.
#   Each one is listed below with its reason so the next person can re-verify or remove it.
#
#   1. ASCII BUILD PATH (required)
#      The build runs against the junction `D:\alrepo` -> the workspace repository, created
#      once with `mklink /J D:\alrepo <repo>`. Two separate problems make a non-ASCII build
#      path unusable:
#        (a) AGP aborts on a non-ASCII project path.
#        (b) Gradle 9.3.1 passes the test worker classpath through an @argfile. When that
#            classpath contains non-ASCII characters, the argfile no longer resolves
#            gradle-worker.jar, and every test worker dies at startup with
#            "ClassNotFoundException: worker.org.gradle.process.internal.worker.GradleWorkerMain".
#            That failure is silent in the console output: the test task reports only
#            "Test process encountered an unexpected problem" with no test results at all.
#      Building through the junction fixes both. The workspace repository itself is the same
#      physical directory, so no sources are copied and edits take effect immediately.
#
#   2. ASCII GRADLE_USER_HOME (required, same reason as 1b)
#      `D:\al-tools` -> <repo>\.tools. GRADLE_USER_HOME must be ASCII too because the worker
#      classpath argfile starts with <GRADLE_USER_HOME>\caches\<version>\workerMain\gradle-worker.jar.
#
#   3. Proxies for the Gradle daemon
#      Direct connections to dl.google.com / repo.maven.apache.org time out on this machine.
#      Configured via `systemProp.*` in <GRADLE_USER_HOME>/gradle.properties, because the
#      Gradle daemon does not use the Windows system proxy.
#
#   4. ANDROID_USER_HOME redirect (required)
#      AGP wants to create `<user>\.android\debug.keystore` and its .lock file, but that
#      directory is not writable here. ANDROID_USER_HOME points at a writable directory
#      containing a locally generated `debug.keystore` (AGP's default debug credentials).
#      This keystore is for local controlled testing only and is not the official signing key.
#
#   5. Toolchain vendor override
#      Upstream requires `jvm.toolchain.vendor=jetbrains` (JBR with JCEF). That JBR cannot be
#      downloaded here (DNS for cache-redirector.jetbrains.com / *.cloudfront.net does not
#      resolve, and the upstream CI helper downloads it with certificate verification
#      disabled, which project rules forbid). `local.properties` blanks the vendor so the
#      local Temurin 21 is accepted.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File tools/build_android.ps1 [-Task :app:android:assembleDebug]
#
# NOTE: This file is intentionally ASCII-only. Windows PowerShell 5.1 reads .ps1 as ANSI
# unless a BOM is present, which corrupts non-ASCII characters and can break parsing.

param(
    [string]$Task = ":app:android:assembleDebug"
)

$ErrorActionPreference = 'Stop'

# ---- paths -----------------------------------------------------------------
# `buildRoot` / `toolsRoot` are the ASCII junctions (reason 1 and 2). The workspace paths are
# only used to create and verify them.
$workspaceRepo = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$buildRoot     = 'D:\alrepo'
$toolsRoot     = 'D:\al-tools'
$jdk           = Join-Path $toolsRoot 'jdk21\jdk-21.0.12.1+1'
$gradleBin     = Join-Path $toolsRoot 'gradle-9.3.1\gradle-9.3.1\bin\gradle.bat'
$gradleHome    = Join-Path $toolsRoot 'gradle-home'
$androidSdk    = Join-Path $toolsRoot 'android-sdk'
$userHome      = Join-Path $env:TEMP 'al-user-home'

# ---- 1. ensure the ASCII junctions exist ----------------------------------
if (-not (Test-Path $buildRoot)) {
    Write-Output "[1/4] creating junction $buildRoot -> $workspaceRepo"
    cmd /c "mklink /J `"$buildRoot`" `"$workspaceRepo`"" | Out-Null
}
if (-not (Test-Path $toolsRoot)) {
    Write-Output "[1/4] creating junction $toolsRoot -> $(Join-Path $workspaceRepo '.tools')"
    cmd /c "mklink /J `"$toolsRoot`" `"$(Join-Path $workspaceRepo '.tools')`"" | Out-Null
}
foreach ($p in @($buildRoot, $toolsRoot)) {
    if (-not (Test-Path $p)) { Write-Error "missing junction: $p"; exit 2 }
    if ($p -match '[^\x20-\x7E]') { Write-Error "ASCII path required, got: $p"; exit 2 }
}
foreach ($p in @($jdk, $gradleBin, $gradleHome, $androidSdk)) {
    if (-not (Test-Path $p)) { Write-Error "missing component: $p"; exit 2 }
}

# ---- 2. ensure a debug keystore exists in a writable Android user home -----
Write-Output "[2/4] preparing ANDROID_USER_HOME at $userHome"
$keyDir = Join-Path $userHome '.android'
$ks     = Join-Path $keyDir 'debug.keystore'
New-Item -ItemType Directory -Force -Path $keyDir | Out-Null
if (-not (Test-Path $ks)) {
    & (Join-Path $jdk 'bin\keytool.exe') -genkeypair -v -keystore $ks `
        -storepass android -keypass android -alias androiddebugkey `
        -keyalg RSA -keysize 2048 -validity 10000 `
        -dname "CN=Android Debug, O=Android, C=US" | Out-Null
    Write-Output "      generated local debug keystore (test signing only)"
} else {
    Write-Output "      reusing existing debug keystore"
}

# ---- 3. environment (process-scoped only; no global changes) --------------
Write-Output "[3/4] configuring environment (process-scoped)"
$env:JAVA_HOME         = $jdk
$env:GRADLE_USER_HOME  = $gradleHome
$env:ANDROID_HOME      = $androidSdk
$env:ANDROID_SDK_ROOT  = $androidSdk
$env:ANDROID_USER_HOME = $userHome
$env:PATH              = "$jdk\bin;$androidSdk\platform-tools;$env:PATH"

# ---- 4. build --------------------------------------------------------------
Write-Output "[4/4] building $Task"
$log = Join-Path $buildRoot 'build-last.txt'
$sw = [Diagnostics.Stopwatch]::StartNew()
# Note: Gradle writes harmless warnings to stderr ("Couldn't open current thread" from its
# file watcher, and WMI query failures). With $ErrorActionPreference='Stop' those must not
# abort the script, so temporarily relax it around the native invocation.
$prevEap = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
& $gradleBin --project-dir $buildRoot $Task `
    "-Dorg.gradle.java.installations.paths=$jdk" `
    "-Dfile.encoding=UTF-8" `
    --console=plain --no-daemon --no-configuration-cache 2>&1 |
    Out-File -FilePath $log -Encoding utf8
$code = $LASTEXITCODE
$ErrorActionPreference = $prevEap
$sw.Stop()

Write-Output ""
Write-Output "exit=$code  elapsed=$([math]::Round($sw.Elapsed.TotalMinutes,1)) min  log=$log"
Select-String -Path $log -Pattern 'BUILD SUCCESSFUL|BUILD FAILED|actionable tasks' |
    Select-Object -First 4 | ForEach-Object { Write-Output ("  " + $_.Line.Trim()) }

# A failing test task is easy to misread: when a test worker cannot start, Gradle prints no
# test results at all. Surface the real cause instead of leaving it buried in the log.
$workerFailure = Select-String -Path $log -Pattern 'GradleWorkerMain|Test process encountered an unexpected problem' |
    Select-Object -First 2
if ($workerFailure) {
    Write-Output ""
    Write-Output "  !! test worker could not start (see reason 1b in this script)"
    $workerFailure | ForEach-Object { Write-Output ("     " + $_.Line.Trim()) }
}

$apkDir = Join-Path $buildRoot 'app\android\build\outputs\apk\default\debug'
if (Test-Path $apkDir) {
    Get-ChildItem $apkDir -Filter *.apk | ForEach-Object {
        $h = (Get-FileHash $_.FullName -Algorithm SHA256).Hash
        Write-Output ("  APK {0}  {1} bytes  sha256={2}" -f $_.Name, $_.Length, $h)
    }
}

exit $code
