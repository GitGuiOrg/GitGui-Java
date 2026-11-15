# Logic shared by the scripts in this folder: locating Maven, the JDK and their executables.
# Load it by dot-sourcing: . (Join-Path $PSScriptRoot 'toolchain.ps1')
# The caller may set $LogTag (message prefix) before loading it.

$MinJava = 25
$ProjectDir = Split-Path -Parent $PSScriptRoot
if (-not $LogTag) { $LogTag = 'gitgui' }

function Write-Info($msg) { Write-Host "[$LogTag] $msg" -ForegroundColor Cyan }
function Write-Warn($msg) { Write-Host "[$LogTag] $msg" -ForegroundColor Yellow }
function Write-Step($msg) { Write-Host "[$LogTag] $msg" -ForegroundColor DarkGray }

# ---------------------------------------------------------------- Maven

function Resolve-MavenCandidate([string]$candidate) {
    # Accepts mvn.cmd, mvn, mvnw.cmd or the Maven folder (containing bin\mvn.cmd).
    if ([string]::IsNullOrWhiteSpace($candidate)) { return $null }
    $c = [Environment]::ExpandEnvironmentVariables($candidate.Trim('"'))
    if (Test-Path -LiteralPath $c -PathType Leaf) { return (Resolve-Path -LiteralPath $c).Path }
    foreach ($rel in @('bin\mvn.cmd', 'bin\mvn.bat', 'mvn.cmd', 'bin\mvn')) {
        $p = Join-Path $c $rel
        if (Test-Path -LiteralPath $p -PathType Leaf) { return (Resolve-Path -LiteralPath $p).Path }
    }
    return $null
}

function Get-MavenSearchRoots {
    # Ordered from the most to the least likely location, so the search stops early.
    $pf = $env:ProgramFiles
    $pf86 = ${env:ProgramFiles(x86)}
    $home_ = $env:USERPROFILE
    $roots = @(
        (Join-Path $ProjectDir '.maven'),
        (Join-Path $env:ProgramData 'chocolatey\lib\maven'),
        (Join-Path $home_ 'scoop\apps\maven'),
        (Join-Path $env:LOCALAPPDATA 'Microsoft\WinGet\Packages'),
        (Join-Path $pf 'Apache'), (Join-Path $pf 'Maven'), (Join-Path $pf 'apache-maven'),
        $(if ($pf86) { Join-Path $pf86 'Apache' }),
        (Join-Path $home_ '.sdkman\candidates\maven'),
        (Join-Path $home_ '.m2\wrapper\dists'),
        'C:\tools', 'C:\Tools', 'C:\apache', 'C:\maven', 'C:\dev', 'C:\dev\tools', 'C:\opt',
        (Join-Path $home_ 'tools'), (Join-Path $home_ 'apps'), (Join-Path $home_ 'Apache'),
        (Join-Path $env:LOCALAPPDATA 'Programs'),
        (Join-Path $env:APPDATA 'JetBrains'),
        "$pf\JetBrains", "$home_\AppData\Local\JetBrains\Toolbox\apps", "$pf\Eclipse Foundation",
        "$home_\.p2\pool\plugins", "$pf\Microsoft VS Code"
    )
    $roots | Where-Object { $_ -and (Test-Path -LiteralPath $_ -ErrorAction SilentlyContinue) }
}

function Find-Maven([string]$MavenPath) {
    # 1. parameter
    if ($MavenPath) {
        $r = Resolve-MavenCandidate $MavenPath
        if ($r) { return @{ Path = $r; Source = '-MavenPath' } }
        Write-Warn "-MavenPath '$MavenPath' is not valid, continuing the search."
    }
    # 2. GITGUI_MAVEN
    $r = Resolve-MavenCandidate $env:GITGUI_MAVEN
    if ($r) { return @{ Path = $r; Source = 'GITGUI_MAVEN' } }

    # 3. PATH
    foreach ($name in @('mvn.cmd', 'mvn')) {
        $cmd = Get-Command $name -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($cmd) { return @{ Path = $cmd.Source; Source = 'PATH' } }
    }

    # 4. environment variables
    foreach ($v in @('MAVEN_HOME', 'M2_HOME', 'MAVEN_HOME_DIR', 'MVN_HOME')) {
        foreach ($scope in @('Process', 'User', 'Machine')) {
            $val = [Environment]::GetEnvironmentVariable($v, $scope)
            if (-not $val) { continue }
            $r = Resolve-MavenCandidate $val
            if ($r) { return @{ Path = $r; Source = "variable $v" } }
        }
    }

    # 5. the project's Maven Wrapper
    $wrapper = Join-Path $ProjectDir 'mvnw.cmd'
    if (Test-Path -LiteralPath $wrapper) { return @{ Path = $wrapper; Source = 'project Maven Wrapper' } }

    # 6. search in common folders (limited depth to stay fast)
    foreach ($root in Get-MavenSearchRoots) {
        $found = Get-ChildItem -LiteralPath $root -Filter 'mvn.cmd' -Recurse -Depth 4 -File -ErrorAction SilentlyContinue |
            Sort-Object FullName -Descending | Select-Object -First 1
        if ($found) { return @{ Path = $found.FullName; Source = "search in $root" } }
    }
    return $null
}

# ---------------------------------------------------------------- JDK

function Get-JavaMajor([string]$javaExe) {
    # In Windows PowerShell 5.1 the stderr of a native executable becomes an error: with Stop it would discard the result.
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $out = & $javaExe -version 2>&1 | Out-String
        if ($out -match 'version "(\d+)(?:\.(\d+))?') {
            $major = [int]$Matches[1]
            if ($major -eq 1 -and $Matches[2]) { $major = [int]$Matches[2] }   # 1.8 -> 8
            return $major
        }
    } catch { } finally { $ErrorActionPreference = $prev }
    return 0
}

function Test-JdkHome([string]$jdkHome) {
    # Returns @{ Home; Major } when the folder is a JDK new enough for the project, otherwise $null.
    if ([string]::IsNullOrWhiteSpace($jdkHome)) { return $null }
    $exe = Join-Path $jdkHome 'bin\java.exe'
    if (-not (Test-Path -LiteralPath $exe)) { return $null }
    $major = Get-JavaMajor $exe
    if ($major -ge $MinJava) { return @{ Home = $jdkHome; Major = $major } }
    return $null
}

function Find-Jdk {
    # Fast path: JAVA_HOME, java on the PATH, the project's .jdk folder. The first match wins.
    $fast = New-Object System.Collections.Generic.List[string]
    if ($env:JAVA_HOME) { $fast.Add($env:JAVA_HOME) }
    $inPath = Get-Command java.exe -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($inPath) { $fast.Add((Split-Path (Split-Path $inPath.Source -Parent) -Parent)) }
    $local = Join-Path $ProjectDir '.jdk'
    if (Test-Path -LiteralPath $local) {
        $fast.Add($local)
        Get-ChildItem -LiteralPath $local -Directory -ErrorAction SilentlyContinue | ForEach-Object { $fast.Add($_.FullName) }
    }
    foreach ($h in ($fast | Select-Object -Unique)) {
        $r = Test-JdkHome $h
        if ($r) { return $r }
    }

    # Slow path: scan the usual install folders and keep the newest JDK.
    $pf = $env:ProgramFiles
    $pf86 = ${env:ProgramFiles(x86)}
    $roots = @("$pf\Eclipse Adoptium", "$pf\Java", "$pf\Microsoft", "$pf\Amazon Corretto", "$pf\Zulu", "$pf\BellSoft",
               "$pf\Semeru", "$pf\GraalVM", "$pf\JetBrains", "$pf\OpenJDK", "$pf\RedHat", "$pf\Temurin",
               $(if ($pf86) { "$pf86\Java" }), 'C:\tools', 'C:\Java', 'C:\jdk',
               "$env:USERPROFILE\.jdks", "$env:USERPROFILE\scoop\apps", "$env:USERPROFILE\.sdkman\candidates\java",
               "$env:LOCALAPPDATA\Programs", "$env:ProgramData\chocolatey\lib")
    $candidates = New-Object System.Collections.Generic.List[string]
    foreach ($root in $roots) {
        if (-not $root -or -not (Test-Path -LiteralPath $root -ErrorAction SilentlyContinue)) { continue }
        Get-ChildItem -LiteralPath $root -Filter 'java.exe' -Recurse -Depth 4 -File -ErrorAction SilentlyContinue |
            Where-Object { $_.DirectoryName -like '*\bin' } |
            ForEach-Object { $candidates.Add((Split-Path $_.DirectoryName -Parent)) }
    }
    $best = $null
    foreach ($h in ($candidates | Select-Object -Unique)) {
        $r = Test-JdkHome $h
        if ($r -and (-not $best -or $r.Major -gt $best.Major)) { $best = $r }
    }
    return $best
}

# ---------------------------------------------------------------- shared setup

function Initialize-Toolchain {
    <#
      Locates the JDK and Maven, sets JAVA_HOME for the current process and returns @{ Jdk; Maven }.
      Prints install instructions and exits the calling script when something required is missing,
      unless -Soft is given (used by "run.ps1 -ShowMaven", which only reports).
    #>
    param(
        [string]$MavenPath,
        [switch]$SkipMaven,
        [switch]$Soft
    )
    $result = @{ Jdk = $null; Maven = $null }

    $jdk = Find-Jdk
    if ($jdk) {
        Write-Info "JDK $($jdk.Major) found in $($jdk.Home)"
        if ($env:JAVA_HOME -ne $jdk.Home) {
            $env:JAVA_HOME = $jdk.Home
            $env:Path = (Join-Path $jdk.Home 'bin') + ';' + $env:Path
            Write-Info 'JAVA_HOME set for this run only.'
        }
        $result.Jdk = $jdk
    } else {
        Write-Warn "No JDK >= ${MinJava} found. For example, install Temurin ${MinJava} (https://adoptium.net/ or: winget install EclipseAdoptium.Temurin.${MinJava}.JDK)"
        if (-not $Soft) { exit 1 }
    }

    if (-not $SkipMaven) {
        $mvn = Find-Maven $MavenPath
        if ($mvn) {
            Write-Info "Maven: $($mvn.Path)  [$($mvn.Source)]"
            $result.Maven = $mvn
        } else {
            Write-Warn 'Maven not found. Options:'
            Write-Warn '  - winget install Apache.Maven     (or: choco install maven / scoop install maven)'
            Write-Warn '  - download https://maven.apache.org/download.cgi and pass the folder with -MavenPath'
            Write-Warn '  - or set the GITGUI_MAVEN environment variable (or MAVEN_HOME)'
            exit 1
        }
    }
    return $result
}

function Invoke-Maven($tc, [string[]]$MavenArgs) {
    # Runs Maven from the project folder and exits the calling script with Maven's exit code on failure.
    Push-Location $ProjectDir
    try {
        Write-Step ("mvn " + ($MavenArgs -join ' '))
        & $tc.Maven.Path @MavenArgs
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    } finally {
        Pop-Location
    }
}
