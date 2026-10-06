[CmdletBinding()]
param(
    [string]$DeviceSerial,
    [string]$OutputDirectory,
    [switch]$SkipBuild,
    [switch]$RestartDevice
)

$ErrorActionPreference = 'Stop'
$workspaceRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$localProperties = Join-Path $workspaceRoot 'local.properties'
$sdkLine = Get-Content -LiteralPath $localProperties | Where-Object { $_ -like 'sdk.dir=*' } | Select-Object -First 1
if (-not $sdkLine) { throw 'sdk.dir is not configured in local.properties.' }
$sdkRoot = $sdkLine.Substring('sdk.dir='.Length).Replace('\:', ':').Replace('\\', '\')
$adb = Join-Path $sdkRoot 'platform-tools\adb.exe'
$connected = @(& $adb devices) |
    Where-Object { $_ -match '^\S+\s+device$' } |
    ForEach-Object { ($_ -split '\s+')[0] }
if (-not $DeviceSerial) {
    if ($connected.Count -ne 1) { throw 'Connect exactly one authorised Android device or supply -DeviceSerial.' }
    $DeviceSerial = $connected[0]
}
if ($DeviceSerial -notin $connected) { throw "Android device $DeviceSerial is not connected and authorised." }
$deviceAbi = (& $adb -s $DeviceSerial shell getprop ro.product.cpu.abi).Trim()
if (-not $deviceAbi) { throw "Could not determine the ABI of Android device $DeviceSerial." }

if ($RestartDevice) {
    & $adb -s $DeviceSerial reboot
    if ($LASTEXITCODE -ne 0) { throw "Could not restart Android device $DeviceSerial." }
    & $adb -s $DeviceSerial wait-for-device
    $bootDeadline = (Get-Date).AddMinutes(3)
    do {
        Start-Sleep -Seconds 3
        $bootCompleted = (& $adb -s $DeviceSerial shell getprop sys.boot_completed 2>$null).Trim()
    } while ($bootCompleted -ne '1' -and (Get-Date) -lt $bootDeadline)
    if ($bootCompleted -ne '1') { throw "Android device $DeviceSerial did not finish booting." }
}

& $adb -s $DeviceSerial shell input keyevent KEYCODE_WAKEUP | Out-Null
& $adb -s $DeviceSerial shell wm dismiss-keyguard | Out-Null
& $adb -s $DeviceSerial shell input keyevent KEYCODE_HOME | Out-Null

if (-not $OutputDirectory) {
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $OutputDirectory = Join-Path $workspaceRoot "paper\mapsafe-results\screenshots\manuscript-north-whangarei-$stamp"
}
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null

if (-not $SkipBuild) {
    Push-Location $workspaceRoot
    try {
        & '.\gradlew.bat' --no-daemon ':app:assembleDebug' ':app:assembleDebugAndroidTest' "-PMAPSAFE_SCREENSHOT_ABI=$deviceAbi"
        if ($LASTEXITCODE -ne 0) { throw 'The screenshot APK build failed.' }
    }
    finally {
        Pop-Location
    }
}

$appApk = Join-Path $workspaceRoot 'app\build\outputs\apk\debug\app-debug.apk'
$testApk = Join-Path $workspaceRoot 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk'
& $adb -s $DeviceSerial install -r -t $appApk
if ($LASTEXITCODE -ne 0) { throw 'Installing the MapSafe debug APK failed.' }
& $adb -s $DeviceSerial install -r -t $testApk
if ($LASTEXITCODE -ne 0) { throw 'Installing the MapSafe test APK failed.' }

$deviceScreenshotDirectory = '/sdcard/Download/MapSafe-Tier1'
& $adb -s $DeviceSerial shell "mkdir -p $deviceScreenshotDirectory" | Out-Null
& $adb -s $DeviceSerial shell "rm -f $deviceScreenshotDirectory/ms2026-north-whangarei-20260909-*.png" | Out-Null

# Each method runs in a fresh instrumentation process. Running the whole class in
# one process lets ActivityScenario, map layers and DialogFragments leak between
# otherwise independent figures and can produce a valid test assertion against a
# stale/hidden window. The isolated protocol also makes a failed figure explicit.
$testClass = 'com.nextgis.mobile.mapsafe.MapSafeManuscriptScreensDeviceTest'
$testMethods = @(
    'captureNavigationSettingsAndAccess',
    'captureAccessAndVerification',
    'captureCommunityUploadAndPackages',
    'captureSafeguardAndAnonymisation',
    'captureOriginalAndHaloMaskedDatasetsUnobstructed',
    'captureIdentityCreationAndSuccess',
    'captureEncryptProtectScreen',
    'captureEncryptionNotarisationAndDecryption',
    'captureFilenameBoundNotarisationScreen'
)

$animationSettings = @(
    'window_animation_scale',
    'transition_animation_scale',
    'animator_duration_scale'
)
$originalAnimationSettings = @{}
foreach ($setting in $animationSettings) {
    $originalAnimationSettings[$setting] = (& $adb -s $DeviceSerial shell settings get global $setting).Trim()
    & $adb -s $DeviceSerial shell settings put global $setting 0 | Out-Null
}

try {
    foreach ($method in $testMethods) {
        Write-Host "Capturing $method ..." -ForegroundColor Cyan
        & $adb -s $DeviceSerial shell am force-stop com.nextgis.mobile.mapsafe.debug | Out-Null
        & $adb -s $DeviceSerial shell input keyevent KEYCODE_HOME | Out-Null
        $instrumentOutput = @(& $adb -s $DeviceSerial shell am instrument -w -r `
            -e class "$testClass#$method" `
            com.nextgis.mobile.mapsafe.debug.test/androidx.test.runner.AndroidJUnitRunner 2>&1)
        $instrumentOutput | ForEach-Object { Write-Host $_ }
        $failed = $LASTEXITCODE -ne 0 -or
            ($instrumentOutput -join "`n") -match 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|shortMsg=Process crashed'
        if ($failed) { throw "The manuscript screenshot scenario $method failed." }
    }
}
finally {
    foreach ($setting in $animationSettings) {
        $value = $originalAnimationSettings[$setting]
        if ([string]::IsNullOrWhiteSpace($value) -or $value -eq 'null') {
            & $adb -s $DeviceSerial shell settings delete global $setting | Out-Null
        }
        else {
            & $adb -s $DeviceSerial shell settings put global $setting $value | Out-Null
        }
    }
}

$rawDirectory = Join-Path $OutputDirectory 'device-export'
New-Item -ItemType Directory -Path $rawDirectory -Force | Out-Null
& $adb -s $DeviceSerial pull $deviceScreenshotDirectory $rawDirectory
if ($LASTEXITCODE -ne 0) { throw 'Pulling screenshots from the device failed.' }

$screens = Get-ChildItem -LiteralPath $rawDirectory -Recurse -File -Filter 'ms2026-north-whangarei-20260909-*.png'
if ($screens.Count -eq 0) { throw 'No newly named North Whangarei screenshots were found.' }
$manifest = foreach ($screen in $screens) {
    $target = Join-Path $OutputDirectory $screen.Name
    Copy-Item -LiteralPath $screen.FullName -Destination $target -Force
    $copied = Get-Item -LiteralPath $target
    [pscustomobject]@{
        file_name = $copied.Name
        bytes = $copied.Length
        sha256 = (Get-FileHash -LiteralPath $copied.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    }
}
$manifest | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $OutputDirectory 'screenshot-manifest.json') -Encoding utf8
Write-Host "Captured $($screens.Count) North Whangarei screenshots: $OutputDirectory" -ForegroundColor Green
