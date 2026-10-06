[CmdletBinding()]
param(
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._-]*$')]
    [string]$RunLabel = 'preflight'
)

$ErrorActionPreference = 'Stop'
$workspaceRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$trialRoot = Join-Path $workspaceRoot 'paper\mapsafe-results\premium-trial'
$runsRoot = Join-Path $trialRoot 'runs'
$timestamp = Get-Date -Format 'yyyy-MM-dd-HHmmss'
$runId = "$timestamp-$RunLabel"
$runRoot = Join-Path $runsRoot $runId

if (Test-Path -LiteralPath $runRoot) {
    throw "Run directory already exists: $runRoot"
}

$resolvedTrialRoot = (Resolve-Path -LiteralPath $trialRoot).Path
if (-not $resolvedTrialRoot.StartsWith($workspaceRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Unexpected trial root: $resolvedTrialRoot"
}

$directories = @('android', 'browser', 'api', 'acl', 'hashes', 'notes', 'originals', 'redacted')
New-Item -ItemType Directory -Path $runRoot -Force | Out-Null
foreach ($directory in $directories) {
    New-Item -ItemType Directory -Path (Join-Path $runRoot $directory) -Force | Out-Null
}

$templatePath = Join-Path $trialRoot 'EVIDENCE_MANIFEST_TEMPLATE.md'
$manifestPath = Join-Path $runRoot 'manifest.md'
$gitCommit = (& git -C $workspaceRoot rev-parse HEAD).Trim()
$gitBranch = (& git -C $workspaceRoot branch --show-current).Trim()
$createdLocal = (Get-Date).ToString('yyyy-MM-dd HH:mm:ss zzz')
$createdUtc = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')

$manifest = Get-Content -LiteralPath $templatePath -Raw
$manifest = $manifest.Replace('{{RUN_ID}}', $runId)
$manifest = $manifest.Replace('{{RUN_LABEL}}', $RunLabel)
$manifest = $manifest.Replace('{{CREATED_LOCAL}}', $createdLocal)
$manifest = $manifest.Replace('{{CREATED_UTC}}', $createdUtc)
$manifest = $manifest.Replace('{{GIT_COMMIT}}', $gitCommit)
$manifest = $manifest.Replace('{{GIT_BRANCH}}', $gitBranch)
Set-Content -LiteralPath $manifestPath -Value $manifest -Encoding utf8NoBOM

Copy-Item -LiteralPath (Join-Path $trialRoot 'acl-test-matrix.csv') -Destination (Join-Path $runRoot 'acl\acl-test-matrix.csv')
Copy-Item -LiteralPath (Join-Path $trialRoot 'roles.example.json') -Destination (Join-Path $runRoot 'notes\roles.example.json')

$instructions = @"
MapSafe Premium run: $runId

1. Complete manifest.md without adding secrets or unredacted personal email addresses.
2. Keep raw captures in originals/ and publication copies in redacted/.
3. Store Android, browser, API, ACL, and hash evidence in their named subfolders.
4. Back up the completed run to two encrypted locations before cleanup.
5. This directory is ignored by Git; commit only a deliberately redacted manifest.
"@
Set-Content -LiteralPath (Join-Path $runRoot 'README.txt') -Value $instructions -Encoding utf8NoBOM

Write-Host "Created MapSafe Premium evidence workspace:" -ForegroundColor Green
Write-Host $runRoot
Write-Host "Manifest: $manifestPath"
