# AnimekoLocalGuard - tools/build_localguard_standalone.ps1
#
# Standalone compile + run of the :danmaku:localguard pure-logic module.
#
# Why this exists:
#   The upstream Gradle build is the authoritative test runner, but it needs a full
#   Gradle + AGP + Android SDK toolchain and takes minutes. This script compiles
#   commonMain + commonTest + devrun with a project-local JDK and the Kotlin compiler
#   and runs the kotlin.test cases via reflection, so pure logic can be iterated in
#   seconds and the logic can be executed even when Gradle is unavailable.
#
# Toolchain versions MUST match the upstream build (see gradle/libs.versions.toml):
#   Kotlin 2.4.10. Using a different compiler version means the compiler plugin for
#   kotlinx.serialization cannot be loaded (plugin and compiler versions must match),
#   which silently rules out @Serializable in this module.
#
# Honest limitations (must be stated in reports):
#   - Covers PURE LOGIC only (no Android, no Compose, no upstream dependencies).
#   - It does NOT prove the upstream Gradle build passes, nor any on-device behaviour.
#   - When Gradle works, the Gradle test task is authoritative.
#
# NOTE: This file is intentionally ASCII-only. Windows PowerShell 5.1 reads .ps1 as
# ANSI unless a BOM is present, which corrupts non-ASCII comments and can break parsing.
#
# Usage: powershell -ExecutionPolicy Bypass -File tools/build_localguard_standalone.ps1 [-Clean] [-Only <classSubstring>]

param(
    [switch]$Clean,
    # Only run test classes whose name contains this substring (fast triage when one hangs).
    [string]$Only = ""
)

$ErrorActionPreference = 'Stop'
$root   = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$jdk    = Join-Path $root '.tools\jdk21\jdk-21.0.12.1+1'
$java   = Join-Path $jdk 'bin\java.exe'
$dl     = Join-Path $root '.tools\downloads'
$build  = Join-Path $root 'build-localguard'
$cache  = Join-Path $root '.tools\gradle-home\caches\modules-2\files-2.1'

# ---- toolchain (must match upstream Kotlin 2.4.10) --------------------------
$kotlinc = Join-Path $dl 'kotlin-compiler-embeddable-2.4.10.jar'
$stdlib  = Join-Path $dl 'kotlin-stdlib-2.4.10.jar'
$ktest   = Join-Path $dl 'kotlin-test-2.4.10.jar'
$ktestJ5 = Join-Path $dl 'kotlin-test-junit5-2.4.10.jar'

# kotlinx.serialization: the compiler plugin version must equal the compiler version.
$serPlugin = Join-Path $cache 'org.jetbrains.kotlin\kotlin-serialization-compiler-plugin-embeddable\2.4.10'
$serCore   = Join-Path $cache 'org.jetbrains.kotlinx\kotlinx-serialization-core-jvm\1.11.0'
$serJson   = Join-Path $cache 'org.jetbrains.kotlinx\kotlinx-serialization-json-jvm\1.11.0'

$annot   = Join-Path $dl 'annotations-13.0.jar'
$corout  = Join-Path $dl 'kotlinx-coroutines-core-jvm-1.10.2.jar'
$trove   = Join-Path $dl 'trove4j-1.0.20200330.jar'
# The serialization compiler plugin needs kotlin.reflect.jvm.ReflectJvmMapping, which is
# not part of kotlin-compiler-embeddable. 2.4.10 is not in the local Gradle cache, so the
# closest cached version is used; it is only needed for metadata reading.
$reflect = Join-Path $dl 'kotlin-reflect-2.2.21.jar'
$junit5  = Join-Path $dl 'junit-jupiter-api-5.10.2.jar'
$otest4j = Join-Path $dl 'opentest4j-1.3.0.jar'
$apigrd  = Join-Path $dl 'apiguardian-api-1.1.2.jar'
$ctest   = Join-Path $dl 'kotlinx-coroutines-test-jvm-1.10.2.jar'
$jpc     = Join-Path $dl 'junit-platform-commons-1.10.2.jar'

function Find-OneJar([string]$dir, [string]$name) {
    if (-not (Test-Path $dir)) { return $null }
    $f = Get-ChildItem $dir -Recurse -Filter $name -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($f) { return $f.FullName }
    return $null
}

$serPluginJar = Find-OneJar $serPlugin 'kotlin-serialization-compiler-plugin-embeddable-2.4.10.jar'
$serCoreJar   = Find-OneJar $serCore   'kotlinx-serialization-core-jvm-1.11.0.jar'
$serJsonJar   = Find-OneJar $serJson   'kotlinx-serialization-json-jvm-1.11.0.jar'

foreach ($p in @($java, $kotlinc, $stdlib, $ktest, $ktestJ5, $annot, $corout, $trove,
                 $junit5, $otest4j, $apigrd, $ctest, $jpc,
                 $serPluginJar, $serCoreJar, $serJsonJar, $reflect)) {
    if (-not $p -or -not (Test-Path $p)) { Write-Error "missing toolchain component: $p"; exit 2 }
}

if ($Clean -and (Test-Path $build)) { Remove-Item -Recurse -Force $build }
$outMain = Join-Path $build 'classes\main'
$outTest = Join-Path $build 'classes\test'
New-Item -ItemType Directory -Force -Path $outMain, $outTest | Out-Null

$module = Join-Path $root 'danmaku\localguard\src'
$mainRoots = @( (Join-Path $module 'commonMain\kotlin') )
$testRoots = @(
    (Join-Path $module 'commonTest\kotlin'),
    (Join-Path $module 'devrun\kotlin')
)

$runtimeCp = "$stdlib;$annot;$corout;$ktest;$ktestJ5;$junit5;$otest4j;$apigrd;$ctest;$jpc;$serCoreJar;$serJsonJar"

function Invoke-Kotlinc([string[]]$Sources, [string]$Out, [string]$Classpath) {
    # Do NOT name a variable $args: it is a PowerShell automatic variable and
    # assigning to it silently drops the arguments.
    $compilerArgs = @(
        '-cp', "$kotlinc;$runtimeCp;$trove;$serPluginJar;$reflect",
        'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
        '-nowarn', '-jvm-target', '1.8',
        # @Serializable requires the serialization compiler plugin; without it the
        # annotation is rejected as internal/unresolvable.
        "-Xplugin=$serPluginJar",
        '-d', $Out,
        '-classpath', $Classpath,
        '-no-stdlib'
    ) + $Sources
    & $java @compilerArgs
    if ($LASTEXITCODE -ne 0) { Write-Error "kotlinc failed (exit $LASTEXITCODE)"; exit 1 }
}

$mainSources = @()
foreach ($r in $mainRoots) { $mainSources += (Get-ChildItem $r -Recurse -Filter *.kt | ForEach-Object { $_.FullName }) }
$testSources = @()
foreach ($r in $testRoots) { $testSources += (Get-ChildItem $r -Recurse -Filter *.kt | ForEach-Object { $_.FullName }) }

Write-Output "[1/3] compiling commonMain ($($mainSources.Count) files) ..."
# coroutines-core and kotlinx-serialization are api dependencies of this module.
Invoke-Kotlinc $mainSources $outMain "$stdlib;$corout;$serCoreJar;$serJsonJar"

Write-Output "[2/3] compiling commonTest + devrun ($($testSources.Count) files) ..."
$testCp = "$outMain;$runtimeCp"
Invoke-Kotlinc $testSources $outTest $testCp

Write-Output "[3/3] running pure-logic tests ..."
$cp = "$outTest;$outMain;$runtimeCp"
# The runner discovers test classes by scanning this directory, so newly added
# test files are picked up without editing the runner. $Only filters by class name.
$runnerArgs = @($outTest)
if ($Only -ne "") { $runnerArgs += $Only }
& $java -cp $cp me.him188.ani.danmaku.localguard.devrun.LocalGuardTestMain @runnerArgs
$code = $LASTEXITCODE
Write-Output "STANDALONE_POLICY_TESTS exit=$code"
exit $code
