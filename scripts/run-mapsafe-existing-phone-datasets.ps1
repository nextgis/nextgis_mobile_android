[CmdletBinding()]
param(
    [string]$Serial,
    [string]$OutputDirectory,

    [ValidateSet('Quick', 'Paper')]
    [string]$Protocol = 'Quick',

    [switch]$SkipBuild
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$runner = Join-Path $PSScriptRoot 'run-mapsafe-performance-benchmark.ps1'
$arguments = @{
    Protocol = $Protocol
    UseExistingPhoneDatasets = $true
    SkipBuild = $SkipBuild
}
if ($Serial) { $arguments.Serial = $Serial }
if ($OutputDirectory) { $arguments.OutputDirectory = $OutputDirectory }

& $runner @arguments
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
