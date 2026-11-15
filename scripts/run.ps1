<#
.SYNOPSIS
    Starts GitGui with Maven (mvn javafx:run), looking for Maven and the JDK in several places on the PC.

.DESCRIPTION
    Maven search order:
      1. -MavenPath (parameter, mvn.cmd file or Maven folder)
      2. GITGUI_MAVEN environment variable
      3. mvn on the PATH
      4. MAVEN_HOME / M2_HOME / MAVEN_HOME_DIR
      5. the project's Maven Wrapper (mvnw.cmd)
      6. common folders: the project's .maven, Chocolatey, Scoop, WinGet, SDKMAN/Git Bash (~/.sdkman),
         ~/.m2/wrapper, C:\tools, Program Files\Apache, IntelliJ, Eclipse, VS Code
    If it is not found, prints the installation instructions.

    It also looks for a JDK >= 25 (JAVA_HOME, PATH, the project's .jdk, Eclipse Adoptium, Microsoft,
    Oracle, Amazon Corretto, Zulu, ...) and sets JAVA_HOME for this run only if needed.

.PARAMETER Repo
    One or more git repositories to open at startup.

.PARAMETER MavenPath
    Path to mvn.cmd or to the Maven installation folder.

.PARAMETER Build
    Builds and runs the tests before starting (mvn verify) instead of only starting.

.PARAMETER ShowMaven
    Only shows which Maven and which JDK would be used, without starting anything.

.EXAMPLE
    .\scripts\run.ps1
    .\scripts\run.ps1 -Repo C:\git\my-repo
    .\scripts\run.ps1 -MavenPath D:\tools\apache-maven-3.9.9
    .\scripts\run.ps1 -ShowMaven
#>
[CmdletBinding()]
param(
    [string[]]$Repo = @(),
    [string]$MavenPath,
    [switch]$Build,
    [switch]$ShowMaven
)

$ErrorActionPreference = 'Stop'
$LogTag = 'run'
. (Join-Path $PSScriptRoot 'toolchain.ps1')

$tc = Initialize-Toolchain -MavenPath $MavenPath -Soft:$ShowMaven
if ($ShowMaven) { exit 0 }

# Validate the repositories before the slow Maven startup.
$quoted = @()
foreach ($r in $Repo) {
    if (-not (Test-Path -LiteralPath $r -PathType Container)) {
        Write-Warn "Repository folder not found: $r"
        exit 1
    }
    # App arguments: each repository in quotes (the JavaFX plugin respects the quotes).
    $quoted += '"' + (Resolve-Path -LiteralPath $r).Path + '"'
}

$mavenArgs = @('-B')
if ($Build) { $mavenArgs += 'verify' }
$mavenArgs += 'javafx:run'
if ($quoted.Count -gt 0) { $mavenArgs += ('-Djavafx.args=' + ($quoted -join ' ')) }

Invoke-Maven $tc $mavenArgs
