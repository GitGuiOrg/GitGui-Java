# GitGui

Desktop Git client with a JavaFX interface and a git CLI backend (`git` is invoked as an external process).

## Requirements

- **JDK 25**: the project compiles with `maven.compiler.release=25`, so a JDK 21 is not enough.
  Temurin 25 is recommended (`winget install EclipseAdoptium.Temurin.25.JDK`).
- **Maven 3.9+** on the PATH (or given with `-MavenPath` / `GITGUI_MAVEN`).
- **git** on the PATH; an explicit path can be set in `settings.json` (`gitPath`).
- The scripts in `scripts/` are PowerShell (Windows). The Maven build also works
  on Linux and macOS: CI runs on Ubuntu.
- Optional: the GitButler CLI (`but`) for the GitButler features; if it is missing, those views stay disabled.

## Running

```powershell
.\scripts\run.ps1                       # starts the app with mvn javafx:run
.\scripts\run.ps1 -Repo C:\git\my-repo  # opens one or more repositories at startup
.\scripts\run.ps1 -Build                # runs mvn verify before starting
.\scripts\run.ps1 -ShowMaven            # only shows the chosen JDK and Maven, without starting
```

`run.ps1` looks for Maven (parameter, `GITGUI_MAVEN`, PATH, `MAVEN_HOME`, common folders) and a JDK >= 25
(`JAVA_HOME`, PATH, Eclipse Adoptium, Microsoft, Corretto, Zulu, ..., and the project's `.jdk/` folder),
setting `JAVA_HOME` for that run only. The shared logic lives in `scripts/toolchain.ps1` (`$MinJava = 25`).

Equivalent without the script:

```bash
mvn javafx:run
```

## Build and tests

```powershell
.\scripts\test.ps1                 # mvn test
.\scripts\test.ps1 -Verify         # mvn verify (tests + jacoco + SpotBugs), like CI
.\scripts\test.ps1 -Filter GitClientTest
.\scripts\clean.ps1                # removes target/ (-All also removes .jdk/ and .maven/)
```

Or directly with Maven:

```bash
mvn verify
```

`verify` compiles with `release 25`, runs the tests (JUnit 5 + AssertJ), generates the jacoco report and runs
SpotBugs, which fails on Medium or higher level bugs (exclusions in `spotbugs-exclude.xml`).

The standalone jar (JavaFX and dependencies included) is produced in `target/gitgui-<version>-all.jar`:

```bash
java -jar target/gitgui-0.3.0-all.jar
```

## Packaging (Windows)

```powershell
.\scripts\package.ps1
```

Builds the jar and, with `jpackage`, the app image `target/dist/GitGui/GitGui.exe` with a dedicated Java runtime
(Java does not need to be installed on the target machine). Options: `-SkipTests`, `-Dest`, `-MavenPath`, `-Icon <file.ico>`.
`.\scripts\package.ps1 -Type msi` builds an installer instead (Start menu entry, desktop shortcut, folder chooser) and needs the WiX Toolset.
The version and SHA-256 of the produced files are printed at the end.

## Release

```powershell
.\scripts\release.ps1 -GitHubUser <login> -DryRun   # build with the bumped version, then undo everything
.\scripts\release.ps1 -GitHubUser <login>           # bump patch, commit on main, push, create the GitHub release
```

Needs the GitHub CLI (`gh`) signed in with the chosen account. The script bumps the patch version in `pom.xml`
(`-Bump minor|major|none` to change that) in a dedicated `Release <version>` commit on `main`, pushes `main`,
then creates the tag and the release with the jar, the zipped app image (`-Msi` adds the installer) and `SHA256SUMS.txt`.
It must run from a clean `main` that is already pushed, and asks for confirmation before anything leaves your machine.

## CI

`.github/workflows/ci.yml` runs `mvn -B verify` with Temurin 25 on every push to `main` and on every pull
request, with Maven dependency caching.

## Configuration

Settings are stored in `%APPDATA%\GitGui\settings.json` (on Linux/macOS `$XDG_CONFIG_HOME/GitGui`
or `~/.config/GitGui`). The most used keys: `gitPath`, `butPath`, `gitButlerAuto`,
`autoFetchMinutes`, `pullMode`, the diff options (`diffSideBySide`, `diffSyntax`, ...).

## Structure

- `src/main/java/com/github/gitguiorg/gitgui/git`: git CLI invocation, parsers and GitButler client.
- `src/main/java/com/github/gitguiorg/gitgui/service`: settings, repository session, watcher.
- `src/main/java/com/github/gitguiorg/gitgui/ui`: JavaFX views and view models.
- `src/main/java/com/github/gitguiorg/gitgui/model`, `util`: diff/commit models and utilities.

## License

Released under the [MIT License](LICENSE).
