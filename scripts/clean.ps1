<#
.SYNOPSIS
    Removes the build output of GitGui (the target folder).

.DESCRIPTION
    Deletes target/ (classes, jars, reports, jpackage input and the packaged app image or installer).
    It does not need Maven or a JDK. The project's .jdk and .maven folders are kept unless -All is given.

.PARAMETER All
    Also removes the project-local .jdk and .maven folders, if present.

.EXAMPLE
    .\scripts\clean.ps1
    .\scripts\clean.ps1 -All
#>
[CmdletBinding()]
param(
    [switch]$All
)

$ErrorActionPreference = 'Stop'
$LogTag = 'clean'
. (Join-Path $PSScriptRoot 'toolchain.ps1')

$targets = @('target')
if ($All) { $targets += @('.jdk', '.maven') }

$removed = 0
foreach ($name in $targets) {
    $path = Join-Path $ProjectDir $name
    if (-not (Test-Path -LiteralPath $path)) { continue }
    # Safety: only ever delete a folder that sits directly inside the project folder.
    if ((Split-Path -Parent (Resolve-Path -LiteralPath $path).Path) -ne $ProjectDir) {
        Write-Warn "Skipping ${path}: not directly inside the project folder."
        continue
    }
    Write-Step "Removing $path"
    Remove-Item -LiteralPath $path -Recurse -Force
    $removed++
}

if ($removed -eq 0) { Write-Info 'Nothing to clean.' } else { Write-Info "Removed $removed folder(s)." }
