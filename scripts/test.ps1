<#
.SYNOPSIS
    Runs the GitGui tests (mvn test) or the full verification (mvn verify).

.DESCRIPTION
    Finds Maven and a JDK >= 25 like run.ps1 (see toolchain.ps1).
    "mvn test" only runs the unit tests. With -Verify it runs "mvn verify": compilation with release 25,
    tests, the jacoco report and SpotBugs, exactly like CI.

.PARAMETER Verify
    Runs "mvn verify" (tests + jacoco + SpotBugs) instead of "mvn test".

.PARAMETER Filter
    Runs only the matching tests, passed to -Dtest (for example GitClientTest or GitClientTest#stages*).

.PARAMETER MavenPath
    Path to mvn.cmd or to the Maven installation folder.

.EXAMPLE
    .\scripts\test.ps1
    .\scripts\test.ps1 -Verify
    .\scripts\test.ps1 -Filter GitClientTest
#>
[CmdletBinding()]
param(
    [switch]$Verify,
    [string]$Filter,
    [string]$MavenPath
)

$ErrorActionPreference = 'Stop'
$LogTag = 'test'
. (Join-Path $PSScriptRoot 'toolchain.ps1')

$tc = Initialize-Toolchain -MavenPath $MavenPath

$mavenArgs = @('-B', $(if ($Verify) { 'verify' } else { 'test' }))
if ($Filter) {
    $mavenArgs += "-Dtest=$Filter"
    $mavenArgs += '-Dsurefire.failIfNoSpecifiedTests=false'
}

Invoke-Maven $tc $mavenArgs
Write-Info 'Done.'
