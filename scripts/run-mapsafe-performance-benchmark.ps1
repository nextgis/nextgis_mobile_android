[CmdletBinding()]
param(
    [string]$Serial,
    [string]$OutputDirectory,

    [ValidateSet('Quick', 'Paper')]
    [string]$Protocol = 'Paper',

    [switch]$AllowEmulator,

    [switch]$UseExistingPhoneDatasets,

    [switch]$SkipBuild
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$workspaceRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$gradleWrapper = Join-Path $workspaceRoot 'gradlew.bat'
$benchmarkClass = 'com.nextgis.mobile.mapsafe.MapSafePerformanceBenchmarkTest'
$targetPackage = 'com.nextgis.mobile.debug'
$testPackage = 'com.nextgis.mobile.debug.test'
$runner = 'androidx.test.runner.AndroidJUnitRunner'

function Get-AndroidSdkDirectory {
    $propertiesPath = Join-Path $workspaceRoot 'local.properties'
    $sdkProperty = Get-Content -LiteralPath $propertiesPath -ErrorAction Stop |
        Where-Object { $_ -match '^sdk\.dir=' } |
        Select-Object -First 1
    if (-not $sdkProperty) {
        throw "sdk.dir was not found in $propertiesPath"
    }
    $encoded = ($sdkProperty -split '=', 2)[1]
    return $encoded.Replace('\:', ':').Replace('\\', '\')
}

function Invoke-Adb {
    param([string[]]$Arguments)
    $output = @(& $script:adbPath @Arguments 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "ADB failed: adb $($Arguments -join ' ')`n$($output -join [Environment]::NewLine)"
    }
    return $output
}

function Get-ConnectedSerials {
    return @(Invoke-Adb -Arguments @('devices')) |
        Where-Object { $_ -match '^(\S+)\s+device$' } |
        ForEach-Object { $Matches[1] }
}

$sdkDirectory = Get-AndroidSdkDirectory
$script:adbPath = Join-Path $sdkDirectory 'platform-tools\adb.exe'
if (-not (Test-Path -LiteralPath $script:adbPath -PathType Leaf)) {
    throw "ADB was not found at $script:adbPath"
}
if (-not (Test-Path -LiteralPath $gradleWrapper -PathType Leaf)) {
    throw "Gradle wrapper was not found at $gradleWrapper"
}

Invoke-Adb -Arguments @('start-server') | Out-Null
$connected = @(Get-ConnectedSerials)
if ($Serial) {
    if ($Serial -notin $connected) {
        throw "Requested Android device '$Serial' is not connected. Connected devices: $($connected -join ', ')"
    }
}
elseif ($connected.Count -eq 1) {
    $Serial = $connected[0]
}
elseif ($connected.Count -eq 0) {
    throw 'No Android device is connected. Connect and unlock a physical Android phone with USB debugging enabled.'
}
else {
    throw "More than one Android device is connected. Supply -Serial. Devices: $($connected -join ', ')"
}

$model = ((Invoke-Adb -Arguments @('-s', $Serial, 'shell', 'getprop', 'ro.product.model')) -join '').Trim()
$manufacturer = ((Invoke-Adb -Arguments @('-s', $Serial, 'shell', 'getprop', 'ro.product.manufacturer')) -join '').Trim()
$qemu = ((Invoke-Adb -Arguments @('-s', $Serial, 'shell', 'getprop', 'ro.kernel.qemu')) -join '').Trim()
$isEmulator = $Serial.StartsWith('emulator-') -or $qemu -eq '1' -or $model -match 'Emulator|Android SDK built for|sdk_gphone'
if ($isEmulator -and -not $AllowEmulator) {
    throw "Performance results must come from a physical phone; '$Serial' ($manufacturer $model) is an emulator."
}

if ($isEmulator) {
    Write-Warning 'This is an explicitly permitted emulator run. Its timings are preliminary and must not be reported as physical mobile performance.'
    Write-Host "Emulator: $manufacturer $model ($Serial)" -ForegroundColor Yellow
}
else {
    Write-Host "Physical device: $manufacturer $model ($Serial)" -ForegroundColor Green
}
Write-Host "Protocol: $Protocol"
if ($SkipBuild) {
    Write-Host 'Using already-built benchmark APKs.'
}
else {
    Write-Host 'Building benchmark APKs...'
    Push-Location $workspaceRoot
    try {
        & $gradleWrapper --no-daemon --console=plain --warning-mode=none :app:assembleDebug :app:assembleDebugAndroidTest
        if ($LASTEXITCODE -ne 0) {
            throw "Gradle build failed with exit code $LASTEXITCODE."
        }
    }
    finally {
        Pop-Location
    }
}

$appApk = Join-Path $workspaceRoot 'app\build\outputs\apk\debug\app-debug.apk'
$testApk = Join-Path $workspaceRoot 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk'
if (-not (Test-Path -LiteralPath $appApk -PathType Leaf)) { throw "App APK not found: $appApk" }
if (-not (Test-Path -LiteralPath $testApk -PathType Leaf)) { throw "Test APK not found: $testApk" }

Write-Host 'Installing benchmark APKs...'
Invoke-Adb -Arguments @('-s', $Serial, 'install', '-r', $appApk) | Out-Host
Invoke-Adb -Arguments @('-s', $Serial, 'install', '-r', $testApk) | Out-Host
Invoke-Adb -Arguments @('-s', $Serial, 'logcat', '-c') | Out-Null

$stagedInputDirectory = $null
if ($UseExistingPhoneDatasets) {
    $expectedDatasetNames = foreach ($points in @(50, 250, 500, 1000, 2000)) {
        foreach ($profile in @('typical', 'rich')) {
            "field-$points-$profile.geojson"
        }
    }
    $sourceDirectory = '/sdcard/Download/MapSafe'
    $stageId = [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss')
    $temporaryInputDirectory = "/data/local/tmp/mapsafe-existing-input/$stageId"
    $privateInputRelative = "files/mapsafe-existing-input/$stageId"
    $stagedInputDirectory = "/data/user/0/$targetPackage/$privateInputRelative"
    Invoke-Adb -Arguments @('-s', $Serial, 'shell', 'mkdir', '-p', $temporaryInputDirectory) | Out-Null
    Invoke-Adb -Arguments @(
        '-s', $Serial, 'shell', 'run-as', $targetPackage, 'mkdir', '-p', $privateInputRelative
    ) | Out-Null
    foreach ($fileName in $expectedDatasetNames) {
        $source = "$sourceDirectory/$fileName"
        $available = @(Invoke-Adb -Arguments @('-s', $Serial, 'shell', 'test', '-f', $source, ';', 'echo', '$?'))
        if (($available -join '').Trim() -ne '0') {
            throw "Required phone dataset is missing: $source"
        }
        $temporaryFile = "$temporaryInputDirectory/$fileName"
        Invoke-Adb -Arguments @('-s', $Serial, 'shell', 'cp', $source, $temporaryFile) | Out-Null
        Invoke-Adb -Arguments @('-s', $Serial, 'shell', 'chmod', '644', $temporaryFile) | Out-Null
        Invoke-Adb -Arguments @(
            '-s', $Serial, 'shell', 'run-as', $targetPackage,
            'cp', $temporaryFile, "$privateInputRelative/$fileName"
        ) | Out-Null
    }
    Write-Host "Staged 10 existing Downloads/MapSafe datasets without modifying their source files." -ForegroundColor Green
    Write-Host "Private staged input: $stagedInputDirectory"
}

if ($Protocol -eq 'Quick') {
    Write-Host 'Running 1 warm-up and 5 measurements for each benchmark cell...'
}
else {
    Write-Host 'Running 5 warm-ups and 30 measurements for each benchmark cell...'
}
$instrumentationArguments = @(
    '-s', $Serial, 'shell', 'am', 'instrument', '-w', '-r',
    '-e', 'class', $benchmarkClass,
    '-e', 'mapsafe.protocol', $Protocol.ToLowerInvariant(),
    '-e', 'mapsafe.allowEmulator', $AllowEmulator.IsPresent.ToString().ToLowerInvariant()
)
if ($stagedInputDirectory) {
    $instrumentationArguments += @('-e', 'mapsafe.inputDirectory', $stagedInputDirectory)
}
$instrumentationArguments += "$testPackage/$runner"
$instrumentation = Invoke-Adb -Arguments $instrumentationArguments
$instrumentation | Out-Host
$instrumentationText = $instrumentation -join [Environment]::NewLine
if ($instrumentationText -match 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|shortMsg=') {
    throw 'The Android performance benchmark failed. Review the instrumentation output above.'
}

$remoteRoot = "/sdcard/Android/data/$targetPackage/files/mapsafe-benchmark"
$remoteRuns = @(
    @(Invoke-Adb -Arguments @('-s', $Serial, 'shell', 'ls', '-1t', $remoteRoot)) |
        Where-Object { $_ -match '^\d{8}-\d{6}$' }
)
if ($remoteRuns.Count -eq 0) {
    throw "The benchmark completed but no result directory was found under $remoteRoot."
}
$latestRun = $remoteRuns[0]
if (-not $OutputDirectory) {
    $OutputDirectory = Join-Path $workspaceRoot "app\build\reports\mapsafe-performance\$latestRun"
}
$resolvedOutputParent = Split-Path -Parent $OutputDirectory
if (-not (Test-Path -LiteralPath $resolvedOutputParent)) {
    New-Item -ItemType Directory -Path $resolvedOutputParent -Force | Out-Null
}

Write-Host 'Pulling raw measurements and summary...'
Invoke-Adb -Arguments @('-s', $Serial, 'pull', "$remoteRoot/$latestRun", $OutputDirectory) | Out-Host

Write-Host ''
Write-Host 'MapSafe performance benchmark completed.' -ForegroundColor Green
Write-Host "Results: $OutputDirectory"
Write-Host "Summary: $(Join-Path $OutputDirectory 'summary.csv')"
Write-Host "Masking phases: $(Join-Path $OutputDirectory 'masking-phase-measurements.csv')"
Write-Host "Run progress: $(Join-Path $OutputDirectory 'run-progress.json')"
Write-Host "Dataset manifest: $(Join-Path $OutputDirectory 'dataset-manifest.csv')"
Write-Host "Reusable samples: $(Join-Path $OutputDirectory 'datasets')"
Write-Host "LaTeX table: $(Join-Path $OutputDirectory 'paper-table.tex')"
Write-Host "Metadata: $(Join-Path $OutputDirectory 'metadata.json')"
