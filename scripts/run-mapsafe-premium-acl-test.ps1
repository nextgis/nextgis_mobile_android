[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$GuardianAccount,
    [Parameter(Mandatory = $true)][string]$PreciseRecipientAccount,
    [Parameter(Mandatory = $true)][string]$AnonymisedRecipientAccount,
    [Parameter(Mandatory = $true)][string]$OutsiderAccount,
    [Parameter(Mandatory = $true)][string]$CommunityName,
    [string]$DeviceSerial,
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$workspaceRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$localProperties = Join-Path $workspaceRoot 'local.properties'
if (-not (Test-Path -LiteralPath $localProperties)) { throw 'local.properties was not found.' }
$sdkLine = Get-Content -LiteralPath $localProperties | Where-Object { $_ -like 'sdk.dir=*' } | Select-Object -First 1
if (-not $sdkLine) { throw 'sdk.dir is not configured in local.properties.' }
$sdkRoot = $sdkLine.Substring('sdk.dir='.Length).Replace('\:', ':').Replace('\\', '\')
$adb = Join-Path $sdkRoot 'platform-tools\adb.exe'
if (-not (Test-Path -LiteralPath $adb)) { throw "adb was not found at $adb" }

$connected = @(& $adb devices) |
    Where-Object { $_ -match '^\S+\s+device$' } |
    ForEach-Object { ($_ -split '\s+')[0] }
if (-not $DeviceSerial) {
    if ($connected.Count -ne 1) {
        throw 'Connect exactly one authorised Android device or supply -DeviceSerial.'
    }
    $DeviceSerial = $connected[0]
}
if ($DeviceSerial -notin $connected) { throw "Android device $DeviceSerial is not connected and authorised." }
$deviceAbi = (& $adb -s $DeviceSerial shell getprop ro.product.cpu.abi).Trim()

if (-not $SkipBuild) {
    Push-Location $workspaceRoot
    try {
        & '.\gradlew.bat' --no-daemon ':app:assembleDebug' ':app:assembleDebugAndroidTest' "-PMAPSAFE_SCREENSHOT_ABI=$deviceAbi"
        if ($LASTEXITCODE -ne 0) { throw 'The debug app and test APK build failed.' }
    }
    finally {
        Pop-Location
    }
}

$appApk = Join-Path $workspaceRoot 'app\build\outputs\apk\debug\app-debug.apk'
$testApk = Join-Path $workspaceRoot 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk'
if (-not (Test-Path -LiteralPath $appApk) -or -not (Test-Path -LiteralPath $testApk)) {
    throw 'The app or instrumentation APK is missing; rerun without -SkipBuild.'
}

& $adb -s $DeviceSerial install -r -t $appApk
if ($LASTEXITCODE -ne 0) { throw 'Installing the MapSafe debug APK failed.' }
& $adb -s $DeviceSerial install -r -t $testApk
if ($LASTEXITCODE -ne 0) { throw 'Installing the MapSafe test APK failed.' }

$instrumentationArgs = @(
    '-s', $DeviceSerial,
    'shell', 'am', 'instrument', '-w', '-r',
    '-e', 'class', 'com.nextgis.mobile.mapsafe.MapSafePremiumAclDeviceTest',
    '-e', 'mapsafe.premium.guardian_account', $GuardianAccount,
    '-e', 'mapsafe.premium.precise_account', $PreciseRecipientAccount,
    '-e', 'mapsafe.premium.anonymised_account', $AnonymisedRecipientAccount,
    '-e', 'mapsafe.premium.outsider_account', $OutsiderAccount,
    '-e', 'mapsafe.premium.community_name', $CommunityName,
    'com.nextgis.mobile.mapsafe.debug.test/androidx.test.runner.AndroidJUnitRunner'
)
Write-Host "Running Premium ACL acceptance on $DeviceSerial..."
& $adb @instrumentationArgs
if ($LASTEXITCODE -ne 0) { throw 'The Premium ACL acceptance test failed.' }

Write-Host ''
Write-Host 'PREMIUM ACL ACCEPTANCE: PASS' -ForegroundColor Green
Write-Host 'Timestamped evidence resources were intentionally retained in the selected Web GIS.'
