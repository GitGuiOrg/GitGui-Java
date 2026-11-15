<#
.SYNOPSIS
    Builds the executable jar and the Windows app image or installer of GitGui.

.DESCRIPTION
    Looks for Maven and a JDK >= 25 like run.ps1 (see toolchain.ps1), runs "mvn package", which produces
    target/gitgui-<version>-all.jar (JavaFX and dependencies included), then uses jpackage to create,
    with a dedicated Java runtime, either:
      - app-image (default): target/dist/GitGui/GitGui.exe, started without Java being installed;
      - msi: target/dist/GitGui-<version>.msi, with a Start menu entry, a desktop shortcut and a
        folder chooser. It needs the WiX Toolset (https://wixtoolset.org/), which is checked first.

    The app image or installer is recreated on every run. At the end the version and the SHA-256 of
    the produced files are printed.

.PARAMETER Type
    app-image (default) or msi.

.PARAMETER Icon
    Optional .ico file used for the executable, the shortcuts and the installer.

.PARAMETER SkipTests
    Builds without running the tests.

.PARAMETER Dest
    Destination folder (default: target/dist).

.PARAMETER MavenPath
    Path to mvn.cmd or to the Maven installation folder.

.EXAMPLE
    .\scripts\package.ps1
    .\scripts\package.ps1 -SkipTests
    .\scripts\package.ps1 -Type msi -Icon .\assets\gitgui.ico
#>
[CmdletBinding()]
param(
    [ValidateSet('app-image', 'msi')]
    [string]$Type = 'app-image',
    [string]$Icon,
    [switch]$SkipTests,
    [string]$Dest,
    [string]$MavenPath
)

$ErrorActionPreference = 'Stop'
$LogTag = 'package'
. (Join-Path $PSScriptRoot 'toolchain.ps1')

# Stable id so that a newer .msi upgrades an installed GitGui instead of installing next to it.
$UpgradeUuid = 'c6f0e5d4-3b2a-4f6e-9a1d-7e8b2c4d5f10'

if (-not $Dest) { $Dest = Join-Path $ProjectDir 'target\dist' }

if ($Icon) {
    if (-not (Test-Path -LiteralPath $Icon -PathType Leaf)) { Write-Warn "Icon not found: $Icon"; exit 1 }
    if ([IO.Path]::GetExtension($Icon) -ne '.ico') { Write-Warn "The icon must be a .ico file: $Icon"; exit 1 }
    $Icon = (Resolve-Path -LiteralPath $Icon).Path
}

$tc = Initialize-Toolchain -MavenPath $MavenPath
$jdk = $tc.Jdk

# jpackage must be the one from the chosen JDK, not another JDK on the PATH.
$jpackage = Join-Path $jdk.Home 'bin\jpackage.exe'
if (-not (Test-Path -LiteralPath $jpackage)) {
    Write-Warn "jpackage not found in $($jdk.Home): a full JDK is required, not a JRE."
    exit 1
}

if ($Type -eq 'msi') {
    $wix = @('wix.exe', 'candle.exe') | ForEach-Object { Get-Command $_ -CommandType Application -ErrorAction SilentlyContinue } |
        Select-Object -First 1
    if (-not $wix) {
        Write-Warn 'The WiX Toolset is required to create an .msi and was not found on the PATH. Options:'
        Write-Warn '  - winget install WiXToolset.WiXToolset   (or: dotnet tool install --global wix)'
        Write-Warn '  - or use the default -Type app-image, which does not need WiX'
        exit 1
    }
}

$mavenArgs = @('-B', 'package')
if ($SkipTests) { $mavenArgs += '-DskipTests' }
Invoke-Maven $tc $mavenArgs

$jar = Get-ChildItem -LiteralPath (Join-Path $ProjectDir 'target') -Filter 'gitgui-*-all.jar' -File |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $jar) { throw 'Jar not found: the shade plugin did not produce target/gitgui-*-all.jar.' }

# jpackage only accepts numeric versions: 0.1.0-SNAPSHOT becomes 0.1.0
$fullVersion = $jar.Name -replace '^gitgui-', '' -replace '-all\.jar$', ''
if ($fullVersion -match '^(\d+(?:\.\d+)*)') { $version = $Matches[1] } else { $version = '1.0.0' }

# Recreate the input folder too, otherwise jars of previous versions are left behind.
$inputDir = Join-Path $ProjectDir 'target\jpackage-input'
if (Test-Path -LiteralPath $inputDir) { Remove-Item -LiteralPath $inputDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $inputDir | Out-Null
Copy-Item -LiteralPath $jar.FullName -Destination $inputDir -Force

$jpackageArgs = @(
    '--type', $Type, '--name', 'GitGui', '--app-version', $version,
    '--input', $inputDir, '--main-jar', $jar.Name, '--main-class', 'com.github.gitguiorg.gitgui.Launcher',
    '--dest', $Dest, '--description', 'Git GUI desktop client'
)
if ($Icon) { $jpackageArgs += @('--icon', $Icon) }

if ($Type -eq 'app-image') {
    # jpackage does not overwrite an existing app image: recreate it from scratch on every run.
    $appImage = Join-Path $Dest 'GitGui'
    if (Test-Path -LiteralPath $appImage) {
        Write-Step "Removing the previous app image: $appImage"
        Remove-Item -LiteralPath $appImage -Recurse -Force
    }
    $output = Join-Path $appImage 'GitGui.exe'
} else {
    $jpackageArgs += @('--win-menu', '--win-shortcut', '--win-dir-chooser', '--win-upgrade-uuid', $UpgradeUuid)
    $output = Join-Path $Dest "GitGui-$version.msi"
    if (Test-Path -LiteralPath $output) { Remove-Item -LiteralPath $output -Force }
}

Write-Step "jpackage --type $Type"
& $jpackage @jpackageArgs
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
if (-not (Test-Path -LiteralPath $output)) { throw "jpackage finished but $output was not created." }

Write-Info "Version: $fullVersion"
Write-Info "Jar:     $($jar.FullName)"
Write-Info ("         SHA-256 " + (Get-FileHash -LiteralPath $jar.FullName -Algorithm SHA256).Hash)
Write-Info "Output:  $output"
Write-Info ("         SHA-256 " + (Get-FileHash -LiteralPath $output -Algorithm SHA256).Hash)
