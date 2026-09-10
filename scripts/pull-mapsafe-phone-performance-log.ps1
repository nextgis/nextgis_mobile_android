[CmdletBinding()]
param(
    [string]$Serial,
    [string]$OutputDirectory
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$workspaceRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$remoteLog = '/sdcard/Download/MapSafe/mapsafe-performance-log.csv'

function Get-AndroidSdkDirectory {
    $propertiesPath = Join-Path $workspaceRoot 'local.properties'
    $sdkProperty = Get-Content -LiteralPath $propertiesPath -ErrorAction Stop |
        Where-Object { $_ -match '^sdk\.dir=' } |
        Select-Object -First 1
    if (-not $sdkProperty) { throw "sdk.dir was not found in $propertiesPath" }
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

function Convert-ToDatasetId {
    param([string]$Name)
    $candidate = [IO.Path]::GetFileName($Name).Trim()
    $extensions = @('.pgp', '.gpg', '.geojson', '.json')
    do {
        $changed = $false
        foreach ($extension in $extensions) {
            if ($candidate.EndsWith($extension, [StringComparison]::OrdinalIgnoreCase)) {
                $candidate = $candidate.Substring(0, $candidate.Length - $extension.Length)
                $changed = $true
                break
            }
        }
    } while ($changed)
    return $candidate.Trim()
}

function Convert-ToDouble {
    param([string]$Value)
    return [double]::Parse($Value, [Globalization.CultureInfo]::InvariantCulture)
}

function Get-Percentile {
    param(
        [double[]]$Values,
        [double]$Probability
    )
    $sorted = @($Values | Sort-Object)
    if ($sorted.Count -eq 0) { return [double]::NaN }
    if ($sorted.Count -eq 1) { return $sorted[0] }
    $position = ($sorted.Count - 1) * $Probability
    $lower = [math]::Floor($position)
    $upper = [math]::Ceiling($position)
    if ($lower -eq $upper) { return $sorted[$lower] }
    $fraction = $position - $lower
    return $sorted[$lower] + (($sorted[$upper] - $sorted[$lower]) * $fraction)
}

$script:adbPath = Join-Path (Get-AndroidSdkDirectory) 'platform-tools\adb.exe'
if (-not (Test-Path -LiteralPath $script:adbPath -PathType Leaf)) {
    throw "ADB was not found at $script:adbPath"
}

Invoke-Adb -Arguments @('start-server') | Out-Null
$connected = @(Get-ConnectedSerials)
if ($Serial) {
    if ($Serial -notin $connected) {
        throw "Requested device '$Serial' is not connected. Connected devices: $($connected -join ', ')"
    }
} elseif ($connected.Count -eq 1) {
    $Serial = $connected[0]
} elseif ($connected.Count -eq 0) {
    throw 'No Android device is connected. Connect and unlock the phone with USB debugging enabled.'
} else {
    throw "More than one Android device is connected. Supply -Serial. Devices: $($connected -join ', ')"
}

if (-not $OutputDirectory) {
    $runId = [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss')
    $OutputDirectory = Join-Path $workspaceRoot "app\build\reports\mapsafe-phone-performance\$runId"
}
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$localLog = Join-Path $OutputDirectory 'mapsafe-performance-log.csv'

Write-Host "Pulling MapSafe timings from $Serial..."
Invoke-Adb -Arguments @('-s', $Serial, 'pull', $remoteLog, $localLog) | Out-Host
if (-not (Test-Path -LiteralPath $localLog -PathType Leaf)) {
    throw "The performance log was not copied from $remoteLog."
}

$rows = @(Import-Csv -LiteralPath $localLog)
if ($rows.Count -eq 0) { throw 'The phone performance log contains no measurements.' }
$requiredColumns = @('operation', 'dataset_name')
$availableColumns = @($rows[0].PSObject.Properties.Name)
$missingColumns = @($requiredColumns | Where-Object { $_ -notin $availableColumns })
if ($missingColumns.Count -gt 0) {
    throw "The phone log is missing required columns: $($missingColumns -join ', ')"
}
$hasDurationSeconds = 'duration_seconds' -in $availableColumns
$hasDurationMilliseconds = 'duration_ms' -in $availableColumns
if (-not $hasDurationSeconds -and -not $hasDurationMilliseconds) {
    throw 'The phone log must contain duration_seconds or the earlier duration_ms column.'
}

$normalised = foreach ($row in $rows) {
    $durationSeconds = if ($hasDurationSeconds) {
        Convert-ToDouble $row.duration_seconds
    } else {
        (Convert-ToDouble $row.duration_ms) / 1000.0
    }
    [pscustomobject]@{
        dataset_id = Convert-ToDatasetId $row.dataset_name
        dataset_name = $row.dataset_name
        point_count = $row.point_count
        input_bytes = $row.input_bytes
        output_bytes = $row.output_bytes
        operation = $row.operation
        duration_seconds = $durationSeconds
        device = "$($row.device_manufacturer) $($row.device_model)".Trim()
        android_release = $row.android_release
        app_version = $row.app_version
    }
}

$summary = foreach ($group in ($normalised | Group-Object dataset_id, operation)) {
    $items = @($group.Group)
    $durations = [double[]]@($items | ForEach-Object duration_seconds)
    $pointCount = @($items.point_count | Where-Object { $_ -match '^\d+$' } | Select-Object -First 1)
    $plainBytes = @(
        $items |
            ForEach-Object {
                if ($_.operation -like 'openpgp_encrypt*') { $_.input_bytes }
                elseif ($_.operation -eq 'openpgp_decrypt_verify') { $_.output_bytes }
            } |
            Where-Object { $_ -match '^\d+$' } |
            Select-Object -First 1
    )
    [pscustomobject]@{
        dataset_id = $items[0].dataset_id
        dataset_name = $items[0].dataset_name
        points = if ($pointCount.Count -gt 0) { $pointCount[0] } else { '' }
        plaintext_bytes = if ($plainBytes.Count -gt 0) { $plainBytes[0] } else { '' }
        operation = $items[0].operation
        observations = $items.Count
        median_seconds = '{0:F9}' -f (Get-Percentile $durations 0.5)
        q1_seconds = '{0:F9}' -f (Get-Percentile $durations 0.25)
        q3_seconds = '{0:F9}' -f (Get-Percentile $durations 0.75)
        min_seconds = '{0:F9}' -f (($durations | Measure-Object -Minimum).Minimum)
        max_seconds = '{0:F9}' -f (($durations | Measure-Object -Maximum).Maximum)
        devices = (@($items.device | Sort-Object -Unique) -join '; ')
        android_releases = (@($items.android_release | Sort-Object -Unique) -join '; ')
        app_versions = (@($items.app_version | Sort-Object -Unique) -join '; ')
    }
}

$summaryPath = Join-Path $OutputDirectory 'summary.csv'
$summary | Sort-Object dataset_id, operation | Export-Csv -LiteralPath $summaryPath -NoTypeInformation -Encoding utf8

$markdown = @(
    '# MapSafe physical-phone performance summary'
    ''
    '| Dataset | Points | Size (KiB) | Operation | Runs | Median (s) | Q1 (s) | Q3 (s) |'
    '|---|---:|---:|---|---:|---:|---:|---:|'
)
foreach ($row in ($summary | Sort-Object dataset_id, operation)) {
    $sizeKiB = if ($row.plaintext_bytes) {
        '{0:F1}' -f ([double]$row.plaintext_bytes / 1024.0)
    } else { '' }
    $markdown += "| $($row.dataset_id) | $($row.points) | $sizeKiB | $($row.operation) | $($row.observations) | $($row.median_seconds) | $($row.q1_seconds) | $($row.q3_seconds) |"
}
$markdownPath = Join-Path $OutputDirectory 'summary.md'
$markdown | Set-Content -LiteralPath $markdownPath -Encoding utf8

Write-Host ''
Write-Host 'MapSafe phone performance log retrieved and summarised.' -ForegroundColor Green
Write-Host "Raw log: $localLog"
Write-Host "Summary CSV: $summaryPath"
Write-Host "Readable table: $markdownPath"
