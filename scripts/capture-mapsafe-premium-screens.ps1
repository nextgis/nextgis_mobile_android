[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$GuardianAccount,
    [Parameter(Mandatory = $true)][string]$PreciseRecipientAccount,
    [Parameter(Mandatory = $true)][string]$AnonymisedRecipientAccount,
    [Parameter(Mandatory = $true)][string]$OutsiderAccount,
    [Parameter(Mandatory = $true)][string]$CommunityName,
    [Parameter(Mandatory = $true)][long]$CommunityGroupId,
    [string]$DeviceSerial,
    [string]$OutputDirectory,
    [string[]]$TestMethods,
    [switch]$SkipBuild,
    [switch]$SkipInstall
)

$ErrorActionPreference = 'Stop'
$workspaceRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$localProperties = Join-Path $workspaceRoot 'local.properties'
$sdkLine = Get-Content -LiteralPath $localProperties |
    Where-Object { $_ -like 'sdk.dir=*' } |
    Select-Object -First 1
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

if (-not $OutputDirectory) {
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $OutputDirectory = Join-Path $workspaceRoot "paper\mapsafe-results\screenshots\premium-community-$stamp"
}
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null

if (-not $SkipBuild) {
    Push-Location $workspaceRoot
    try {
        & '.\gradlew.bat' --no-daemon ':app:assembleDebug' ':app:assembleDebugAndroidTest' "-PMAPSAFE_SCREENSHOT_ABI=$deviceAbi"
        if ($LASTEXITCODE -ne 0) { throw 'The Premium screenshot APK build failed.' }
    }
    finally {
        Pop-Location
    }
}

if (-not $SkipInstall) {
    $appApk = Join-Path $workspaceRoot 'app\build\outputs\apk\debug\app-debug.apk'
    $testApk = Join-Path $workspaceRoot 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk'
    & $adb -s $DeviceSerial install -r -t $appApk
    if ($LASTEXITCODE -ne 0) { throw 'Installing the MapSafe debug APK failed.' }
    & $adb -s $DeviceSerial install -r -t $testApk
    if ($LASTEXITCODE -ne 0) { throw 'Installing the MapSafe test APK failed.' }
}

$deviceDirectory = '/sdcard/Download/MapSafe-Tier1'
$screenPrefix = 'ms2026-premium-community-20261006-'
& $adb -s $DeviceSerial shell "mkdir -p $deviceDirectory" | Out-Null

$testClass = 'com.nextgis.mobile.mapsafe.MapSafeManuscriptScreensDeviceTest'
if (-not $TestMethods -or $TestMethods.Count -eq 0) {
    $TestMethods = @(
        'capturePremiumSecurityAndKeys',
        'captureCommunityUploadAndPackages',
        'capturePremiumBmaCommunityPackages',
        'capturePremiumAmberCommunityDatasets',
        'capturePremiumBmaVerificationDecryptionAndMap',
        'capturePremiumOutsiderDenied'
    )
}
function ConvertTo-AdbShellLiteral([string]$Value) {
    if ($Value.Contains("'")) { throw 'Android instrumentation values may not contain apostrophes.' }
    return "'" + $Value + "'"
}
$commonArguments = @(
    '-e', 'mapsafe.premium.guardian_account', (ConvertTo-AdbShellLiteral $GuardianAccount),
    '-e', 'mapsafe.premium.precise_account', (ConvertTo-AdbShellLiteral $PreciseRecipientAccount),
    '-e', 'mapsafe.premium.anonymised_account', (ConvertTo-AdbShellLiteral $AnonymisedRecipientAccount),
    '-e', 'mapsafe.premium.outsider_account', (ConvertTo-AdbShellLiteral $OutsiderAccount),
    '-e', 'mapsafe.premium.community_name', (ConvertTo-AdbShellLiteral $CommunityName),
    '-e', 'mapsafe.premium.community_group_id', "$CommunityGroupId"
)

$animationSettings = @('window_animation_scale', 'transition_animation_scale', 'animator_duration_scale')
$originalAnimationSettings = @{}
foreach ($setting in $animationSettings) {
    $originalAnimationSettings[$setting] = (& $adb -s $DeviceSerial shell settings get global $setting).Trim()
    & $adb -s $DeviceSerial shell settings put global $setting 0 | Out-Null
}

try {
    foreach ($method in $TestMethods) {
        Write-Host "Capturing $method ..." -ForegroundColor Cyan
        & $adb -s $DeviceSerial shell am force-stop com.nextgis.mobile.debug | Out-Null
        & $adb -s $DeviceSerial shell input keyevent KEYCODE_HOME | Out-Null
        $instrumentationArguments = @(
            '-s', $DeviceSerial,
            'shell', 'am', 'instrument', '-w', '-r',
            '-e', 'class', "$testClass#$method"
        ) + $commonArguments + @('com.nextgis.mobile.debug.test/androidx.test.runner.AndroidJUnitRunner')
        $instrumentOutput = @(& $adb @instrumentationArguments 2>&1)
        $instrumentOutput | ForEach-Object { Write-Host $_ }
        $failed = $LASTEXITCODE -ne 0 -or
            ($instrumentOutput -join "`n") -match 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|shortMsg=Process crashed'
        if ($failed) { throw "The Premium screenshot scenario $method failed." }
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
& $adb -s $DeviceSerial pull $deviceDirectory $rawDirectory
if ($LASTEXITCODE -ne 0) { throw 'Pulling Premium screenshots from the device failed.' }

$screens = Get-ChildItem -LiteralPath $rawDirectory -Recurse -File -Filter "$screenPrefix*.png"
if ($screens.Count -eq 0) { throw 'No Premium community screenshots were found.' }
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
Write-Host "Captured $($screens.Count) Premium community screenshots: $OutputDirectory" -ForegroundColor Green
