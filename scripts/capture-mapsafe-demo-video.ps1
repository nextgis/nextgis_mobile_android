[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][ValidateRange(15, 180)][int]$DurationSeconds,
    [string]$DeviceSerial,
    [string]$SegmentName = 'mapsafe-demo-segment',
    [string]$OutputDirectory
)

# This script is prepared for later use; it does not provide or record narration.
# Follow paper/mapsafe-results/premium-trial/video-narration-script.md and record
# separate visual segments so credentials and passphrases can be omitted cleanly.

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

$safeSegment = $SegmentName.ToLowerInvariant() -replace '[^a-z0-9._-]', '-'
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$remote = "/sdcard/Download/MapSafe-Tier1/$safeSegment-$stamp.mp4"
if (-not $OutputDirectory) {
    $OutputDirectory = Join-Path $workspaceRoot 'paper\mapsafe-results\premium-trial\video-segments'
}
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null

Write-Host "Recording $DurationSeconds seconds from $DeviceSerial. Perform only the planned, non-secret actions now."
& $adb -s $DeviceSerial shell screenrecord --time-limit $DurationSeconds --bit-rate 12000000 $remote
if ($LASTEXITCODE -ne 0) { throw 'Android screen recording failed.' }
& $adb -s $DeviceSerial pull $remote $OutputDirectory
if ($LASTEXITCODE -ne 0) { throw 'Pulling the video segment failed.' }
Write-Host "Saved visual segment in $OutputDirectory" -ForegroundColor Green
