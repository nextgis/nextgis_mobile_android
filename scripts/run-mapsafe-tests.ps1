[CmdletBinding()]
param(
    [ValidateSet('Scenario', 'Unit', 'Device', 'All')]
    [string]$Suite = 'Unit',

    [switch]$OpenReport,

    [string]$Avd = 'Pixel_9a',

    [switch]$NoStartEmulator
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$workspaceRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$gradleWrapper = Join-Path $workspaceRoot 'gradlew.bat'
$startedAt = Get-Date
$completedReports = [System.Collections.Generic.List[string]]::new()

if (-not (Test-Path -LiteralPath $gradleWrapper -PathType Leaf)) {
    throw "Gradle wrapper not found: $gradleWrapper"
}

function Write-MapSafeHeading {
    param([string]$Text)
    Write-Host ''
    Write-Host "=== $Text ===" -ForegroundColor Cyan
}

function Invoke-MapSafeGradle {
    param(
        [string]$Name,
        [string[]]$Arguments
    )

    Write-MapSafeHeading $Name
    Write-Host "Workspace: $workspaceRoot"
    Write-Host "Command: gradlew.bat $($Arguments -join ' ')"
    Write-Host ''

    Push-Location $workspaceRoot
    try {
        & $gradleWrapper @Arguments
        if ($LASTEXITCODE -ne 0) {
            throw "$Name failed with exit code $LASTEXITCODE."
        }
    }
    finally {
        Pop-Location
    }
}

function Show-UnitTestSummary {
    $resultsDirectory = Join-Path $workspaceRoot 'app\build\test-results\testDebugUnitTest'
    $reportPath = Join-Path $workspaceRoot 'app\build\reports\tests\testDebugUnitTest\index.html'
    $resultFiles = @(Get-ChildItem -LiteralPath $resultsDirectory -Filter '*.xml' -ErrorAction SilentlyContinue)
    if ($resultFiles.Count -eq 0) {
        Write-Warning "No JVM test result files were found in $resultsDirectory"
        return
    }

    $tests = 0
    $failures = 0
    $errors = 0
    $skipped = 0
    foreach ($resultFile in $resultFiles) {
        [xml]$result = Get-Content -LiteralPath $resultFile.FullName -Raw
        $tests += [int]$result.testsuite.tests
        $failures += [int]$result.testsuite.failures
        $errors += [int]$result.testsuite.errors
        $skipped += [int]$result.testsuite.skipped
    }

    Write-MapSafeHeading 'JVM test summary'
    $passed = $tests - $failures - $errors - $skipped
    Write-Host "Passed:   $passed" -ForegroundColor Green
    Write-Host "Failed:   $($failures + $errors)" -ForegroundColor $(if (($failures + $errors) -eq 0) { 'Green' } else { 'Red' })
    Write-Host "Skipped:  $skipped"
    Write-Host "Total:    $tests"
    if (Test-Path -LiteralPath $reportPath -PathType Leaf) {
        Write-Host "Report:   $reportPath"
        $completedReports.Add($reportPath)
    }
}

function Show-DeviceTestSummary {
    $resultsDirectory = Join-Path $workspaceRoot 'app\build\outputs\androidTest-results\connected\debug'
    $reportPath = Join-Path $workspaceRoot 'app\build\reports\androidTests\connected\debug\index.html'
    Write-MapSafeHeading 'Android device test summary'
    $resultFiles = @(Get-ChildItem -LiteralPath $resultsDirectory -Filter 'TEST-*.xml' -ErrorAction SilentlyContinue)
    if ($resultFiles.Count -gt 0) {
        $tests = 0
        $failures = 0
        $errors = 0
        $skipped = 0
        foreach ($resultFile in $resultFiles) {
            [xml]$result = Get-Content -LiteralPath $resultFile.FullName -Raw
            $tests += [int]$result.testsuites.tests
            $failures += [int]$result.testsuites.failures
            $errors += [int]$result.testsuites.errors
            $skipped += [int]$result.testsuites.skipped
        }
        Write-Host "Passed:   $($tests - $failures - $errors - $skipped)" -ForegroundColor Green
        Write-Host "Failed:   $($failures + $errors)" -ForegroundColor $(if (($failures + $errors) -eq 0) { 'Green' } else { 'Red' })
        Write-Host "Skipped:  $skipped"
        Write-Host "Total:    $tests"
    }
    if (Test-Path -LiteralPath $reportPath -PathType Leaf) {
        Write-Host "Report: $reportPath"
        $completedReports.Add($reportPath)
    }
    else {
        Write-Warning "No connected-device report was found at $reportPath"
    }
}

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

function Get-ConnectedAndroidSerial {
    param([string]$AdbPath)

    $lines = @(& $AdbPath devices)
    return $lines |
        Where-Object { $_ -match '^(\S+)\s+device$' } |
        ForEach-Object { $Matches[1] } |
        Select-Object -First 1
}

function Wait-ForAndroidBoot {
    param(
        [string]$AdbPath,
        [int]$TimeoutSeconds = 240
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $serial = Get-ConnectedAndroidSerial -AdbPath $AdbPath
        if ($serial) {
            $booted = (& $AdbPath -s $serial shell getprop sys.boot_completed 2>$null).Trim()
            if ($booted -eq '1') {
                return $serial
            }
        }
        Start-Sleep -Seconds 2
    }
    throw "Android did not finish booting within $TimeoutSeconds seconds."
}

function Start-MapSafeDevice {
    $sdkDirectory = Get-AndroidSdkDirectory
    $adbPath = Join-Path $sdkDirectory 'platform-tools\adb.exe'
    $emulatorPath = Join-Path $sdkDirectory 'emulator\emulator.exe'
    if (-not (Test-Path -LiteralPath $adbPath -PathType Leaf)) {
        throw "ADB was not found at $adbPath"
    }

    & $adbPath start-server | Out-Null
    $serial = Get-ConnectedAndroidSerial -AdbPath $adbPath
    if (-not $serial) {
        if ($NoStartEmulator) {
            throw 'No Android device is connected and -NoStartEmulator was supplied.'
        }
        if (-not (Test-Path -LiteralPath $emulatorPath -PathType Leaf)) {
            throw "Android Emulator was not found at $emulatorPath"
        }
        Write-MapSafeHeading 'Starting visible Android emulator'
        Write-Host "AVD: $Avd"
        Start-Process -FilePath $emulatorPath -ArgumentList @('-avd', $Avd, '-no-snapshot-save') | Out-Null
    }

    $serial = Wait-ForAndroidBoot -AdbPath $adbPath
    Write-Host "Device ready: $serial" -ForegroundColor Green
    $installedPackage = & $adbPath -s $serial shell pm path com.nextgis.mobile.debug
    if ($installedPackage -match '^package:') {
        $previousPreference = $ErrorActionPreference
        try {
            $ErrorActionPreference = 'Continue'
            & $adbPath -s $serial shell pm clear com.nextgis.mobile.debug 2>&1 | Out-Null
        }
        finally {
            $ErrorActionPreference = $previousPreference
        }
    }
    & $adbPath -s $serial logcat -c

    $logcatStart = @{
        FilePath = $adbPath
        ArgumentList = @('-s', $serial, 'logcat', '-v', 'brief', 'MapSafeTier1:I', '*:S')
        NoNewWindow = $true
        PassThru = $true
    }
    $logcat = Start-Process @logcatStart
    return [pscustomobject]@{
        AdbPath = $adbPath
        Serial = $serial
        Logcat = $logcat
    }
}

function Save-MapSafeDeviceScreenshots {
    param($Device)

    $runName = Get-Date -Format 'yyyyMMdd-HHmmss'
    $destination = Join-Path $workspaceRoot "app\build\reports\mapsafe-device\screenshots\$runName"
    New-Item -ItemType Directory -Path $destination -Force | Out-Null
    $remote = '/sdcard/Download/MapSafe-Tier1'
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $pullOutput = & $Device.AdbPath -s $Device.Serial pull $remote $destination 2>&1
        $pullExitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousPreference
    }
    if ($pullExitCode -eq 0) {
        Write-Host "Screenshots: $destination" -ForegroundColor Green
    }
    else {
        Write-Warning "No Tier 1 screenshots were available to pull from the device: $pullOutput"
    }
}

function Invoke-MapSafeDeviceTests {
    $device = Start-MapSafeDevice
    try {
        Invoke-MapSafeGradle -Name 'MapSafe Android Tier 1 workflow tests' -Arguments (
            $commonArguments + @(':app:connectedDebugAndroidTest')
        )
    }
    finally {
        if ($device.Logcat -and -not $device.Logcat.HasExited) {
            Stop-Process -Id $device.Logcat.Id -ErrorAction SilentlyContinue
        }
        Save-MapSafeDeviceScreenshots -Device $device
    }
    Show-DeviceTestSummary
}

$commonArguments = @('--no-daemon', '--console=plain', '--warning-mode=none', '-PmapsafeLiveTests=true')

try {
    switch ($Suite) {
        'Scenario' {
            Invoke-MapSafeGradle -Name 'MapSafe live workflow scenarios' -Arguments (
                $commonArguments + @(
                    ':app:testDebugUnitTest',
                    '--tests',
                    'com.nextgis.mobile.mapsafe.scenario.MapSafeWorkflowScenarioTest'
                )
            )
            Show-UnitTestSummary
        }
        'Unit' {
            Invoke-MapSafeGradle -Name 'All MapSafe JVM tests' -Arguments (
                $commonArguments + @(':app:testDebugUnitTest')
            )
            Show-UnitTestSummary
        }
        'Device' {
            Invoke-MapSafeDeviceTests
        }
        'All' {
            Invoke-MapSafeGradle -Name 'All MapSafe JVM tests' -Arguments (
                $commonArguments + @(':app:testDebugUnitTest')
            )
            Show-UnitTestSummary
            Invoke-MapSafeDeviceTests
        }
    }

    Write-MapSafeHeading 'Completed'
    Write-Host "Suite:    $Suite"
    Write-Host "Duration: $([math]::Round(((Get-Date) - $startedAt).TotalSeconds, 1)) seconds"
    Write-Host 'Note: blockchain steps marked SIMULATED use an in-memory test ledger; they do not submit a real transaction.' -ForegroundColor Yellow

    if ($OpenReport -and $completedReports.Count -gt 0) {
        Start-Process -FilePath $completedReports[$completedReports.Count - 1]
    }
}
catch {
    Write-Host ''
    Write-Host "MAPSAFE TEST RUN FAILED: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}
