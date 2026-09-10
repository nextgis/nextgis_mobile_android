[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$workspaceRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$datasetScript = Join-Path $workspaceRoot 'scripts\prepare_north_whangarei_sample.py'
$manifestPath = Join-Path $workspaceRoot 'paper\mapsafe-results\datasets\north-whangarei\dataset-manifest.json'
$rolesPath = Join-Path $workspaceRoot 'paper\mapsafe-results\premium-trial\roles.example.json'
$matrixPath = Join-Path $workspaceRoot 'paper\mapsafe-results\premium-trial\acl-test-matrix.csv'
$gradle = Join-Path $workspaceRoot 'gradlew.bat'

Write-Host '1/4 Validating and regenerating the North Whangarei case-study fixture...'
& python $datasetScript
if ($LASTEXITCODE -ne 0) { throw 'Dataset preparation failed.' }

$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
if ($manifest.source.point_count -ne 23) { throw 'The reviewed source must contain 23 points.' }
if ($manifest.source.attribute_field_count -ne 0) { throw 'The source DBF unexpectedly contains attributes.' }
if (-not $manifest.mobile_sample.attributes_are_synthetic) {
    throw 'The mobile sample must explicitly identify its demonstration attributes as synthetic.'
}

Write-Host '2/4 Checking credential-free role and ACL fixtures...'
$rolesText = Get-Content -LiteralPath $rolesPath -Raw
if ($rolesText -match '(?i)password|private[_ -]?key|recovery[_ -]?phrase|api[_ -]?token') {
    throw 'The checked-in roles fixture appears to contain a prohibited secret field.'
}
$roles = $rolesText | ConvertFrom-Json
if ($roles.roles.Count -ne 4) { throw 'The Premium trial fixture must define four roles.' }
$expectedStoryNames = @('Steven', 'Amber', 'BMA representative', 'External non-member')
$actualStoryNames = @($roles.roles | ForEach-Object { $_.story_name })
if (Compare-Object -ReferenceObject $expectedStoryNames -DifferenceObject $actualStoryNames) {
    throw 'The Premium trial fixture does not use the established Steven/Amber/BMA case-study roles.'
}
if ((Import-Csv -LiteralPath $matrixPath).Count -lt 7) { throw 'The ACL acceptance matrix is incomplete.' }

Write-Host '3/4 Running pure community ACL and audience-mapping tests...'
Push-Location $workspaceRoot
try {
    & $gradle --no-daemon ':app:testDebugUnitTest' '--tests' 'com.nextgis.mobile.mapsafe.community.*'
    if ($LASTEXITCODE -ne 0) { throw 'Community unit tests failed.' }

    Write-Host '4/4 Compiling the Premium acceptance and manuscript screenshot tests...'
    & $gradle --no-daemon ':app:compileDebugAndroidTestKotlin'
    if ($LASTEXITCODE -ne 0) { throw 'Android test compilation failed.' }
}
finally {
    Pop-Location
}

Write-Host ''
Write-Host 'PREMIUM READINESS: PASS' -ForegroundColor Green
Write-Host 'No subscription was activated and no hosted resource was changed.'
Write-Host "Runbook: $workspaceRoot\paper\mapsafe-results\premium-trial\README.md"
