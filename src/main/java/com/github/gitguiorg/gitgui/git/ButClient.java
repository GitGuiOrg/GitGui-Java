package com.github.gitguiorg.gitgui.git;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * GitButler CLI ({@code but}) for the opt-in GitButler workspace view. It is not a replacement for
 * {@link GitClient}: but has no log, blame, reflog, stash, tag, submodule or bisect commands, and on first
 * use it creates {@code .git/gitbutler} in the repository.
 */
public final class ButClient {
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final Duration VERSION_TIMEOUT = Duration.ofSeconds(10);
    private static final Map<String, String> ENVIRONMENT = Map.of(
            "BUT_PAGER", "cat", "NO_COLOR", "1", "GIT_TERMINAL_PROMPT", "0");

    private final Path root;
    private final String executable;
    private final CliRunner runner;
    private final CliRunner versionRunner;
    private final Object versionLock = new Object();
    private String versionText;

    private ButClient(Path root, String executable) {
        this.root = root.toAbsolutePath().normalize();
        this.executable = executable;
        this.runner = new CliRunner(this.root, executable, "but", TIMEOUT, ENVIRONMENT);
        this.versionRunner = new CliRunner(this.root, executable, "but", VERSION_TIMEOUT, ENVIRONMENT);
    }

    /**
     * Finds but for {@code root}; null when no executable is installed. An explicit invalid path or a
     * missing repository folder throws.
     */
    public static ButClient find(Path root, String override) {
        Path normalized = root.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized)) {
            throw new IllegalArgumentException("repository folder not found: " + normalized);
        }
        String exe = locate(override, System.getenv("PATH"));
        return exe == null ? null : new ButClient(normalized, exe);
    }

    /** Version of the configured but executable, e.g. {@code but 0.23.3}; throws when it cannot run. */
    public static String versionOf(String override) {
        String exe = locate(override, System.getenv("PATH"));
        if (exe == null) throw new IllegalStateException("but executable not found");
        return new ButClient(Path.of("."), exe).version();
    }

    public Path root() { return root; }

    public String path() { return executable; }

    /** Cached output of {@code but --version}. */
    public String version() {
        synchronized (versionLock) {
            if (versionText == null) {
                String text = versionRunner.runStdout(null, "--version").strip();
                if (text.isBlank()) throw new IllegalStateException("but --version returned no output");
                versionText = text;
            }
            return versionText;
        }
    }

    /** Human-readable workspace status ({@code but status}); creates .git/gitbutler on first use. */
    public String workspaceStatus() {
        return workspaceStatus(null);
    }

    /** Cancellable variant of {@link #workspaceStatus()}. */
    public String workspaceStatus(CancelToken cancel) {
        return runner.run(cancel, "-C", root.toString(), "status");
    }

    /** Workspace status as JSON ({@code but --json status}). */
    public String workspaceStatusJson() {
        return workspaceStatusJson(null);
    }

    /** Cancellable variant of {@link #workspaceStatusJson()}. */
    public String workspaceStatusJson(CancelToken cancel) {
        String json = runner.runStdout(cancel, "--json", "-C", root.toString(), "status");
        if (json.isBlank()) throw new IllegalStateException("but status returned no JSON");
        return json;
    }

    /** Parsed workspace status together with the raw JSON, so callers can fall back when parsing fails. */
    public record WorkspaceStatus(ButStatus status, String rawJson, String warning) {}

    /** Runs and parses {@code but --json status}; a parse failure keeps the raw JSON and reports a warning. */
    public WorkspaceStatus status() {
        return status(null);
    }

    /** Cancellable variant of {@link #status()}. */
    public WorkspaceStatus status(CancelToken cancel) {
        return parseWorkspace(workspaceStatusJson(cancel));
    }

    static WorkspaceStatus parseWorkspace(String rawJson) {
        try {
            return new WorkspaceStatus(ButStatus.parse(rawJson), rawJson, null);
        } catch (IllegalArgumentException e) {
            return new WorkspaceStatus(null, rawJson,
                    e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    /** Branches that exist locally or remotely but are not applied to the workspace. */
    public List<ButBranch> unappliedBranches() {
        return unappliedBranches(null);
    }

    /** Cancellable variant of {@link #unappliedBranches()}. */
    public List<ButBranch> unappliedBranches(CancelToken cancel) {
        String json = runner.runStdout(cancel, "--json", "-C", root.toString(), "branch", "list");
        if (json.isBlank()) throw new IllegalStateException("but branch list returned no JSON");
        return ButBranches.parse(json);
    }

    /** Applies a branch to the workspace ({@code but apply}). */
    public void applyBranch(String branch, CancelToken cancel) {
        requireBranch(branch);
        runner.run(cancel, "-C", root.toString(), "apply", branch);
    }

    /** Removes a branch from the workspace without deleting it ({@code but unapply}). */
    public void unapplyBranch(String branch, CancelToken cancel) {
        requireBranch(branch);
        runner.run(cancel, "-C", root.toString(), "unapply", branch);
    }

    /** Creates a new applied branch without stacking it ({@code but branch new}). */
    public void createBranch(String name, CancelToken cancel) {
        requireBranch(name);
        if (name.startsWith("-")) throw new IllegalArgumentException("branch name is invalid");
        runner.run(cancel, "-C", root.toString(), "branch", "new", name);
    }

    /** Creates a new applied branch directly above another one ({@code but branch new --above}). */
    public void createBranchAbove(String name, String above, CancelToken cancel) {
        createBranch(name, above, "--above", cancel);
    }

    /** Creates a new applied branch directly below another one ({@code but branch new --below}). */
    public void createBranchBelow(String name, String below, CancelToken cancel) {
        createBranch(name, below, "--below", cancel);
    }

    private void createBranch(String name, String anchor, String position, CancelToken cancel) {
        requireBranch(name);
        requireBranch(anchor);
        if (name.startsWith("-")) throw new IllegalArgumentException("branch name is invalid");
        runner.run(cancel, "-C", root.toString(), "branch", "new", name, position, anchor);
    }

    /** Renames an applied branch while keeping its commits and stack position ({@code but reword}). */
    public void renameBranch(String branch, String newName, CancelToken cancel) {
        requireBranch(branch);
        requireBranch(newName);
        if (newName.startsWith("-")) throw new IllegalArgumentException("branch name is invalid");
        runner.run(cancel, "-C", root.toString(), "reword", branch, "-m", newName);
    }

    /** Restacks an applied branch directly above another one ({@code but move --above}). */
    public void moveBranchAbove(String branch, String above, CancelToken cancel) {
        requireBranch(branch);
        requireBranch(above);
        if (branch.equals(above)) throw new IllegalArgumentException("target branch must differ");
        runner.run(cancel, "-C", root.toString(), "move", branch, "--above", above);
    }

    /** Commits all uncommitted changes to an applied branch ({@code but commit -b}). */
    public void commitChanges(String branch, String message, CancelToken cancel) {
        commitChanges(branch, message, List.of(), cancel);
    }

    /** Commits only the change at {@code path} to an applied branch. */
    public void commitChanges(String branch, String message, String path, CancelToken cancel) {
        commitChanges(branch, message, List.of(path == null ? "" : path), cancel);
    }

    /** Commits the selected paths, or all changes when {@code paths} is empty. */
    public void commitChanges(String branch, String message, List<String> paths, CancelToken cancel) {
        List<String> selected = paths == null ? List.of() : paths.stream()
                .filter(path -> path != null && !path.isBlank()).toList();
        if (paths != null && !paths.isEmpty() && selected.isEmpty()) {
            throw new IllegalArgumentException("change path is required");
        }
        List<String> command = commitCommand(branch, message);
        command.addAll(selected);
        runner.run(cancel, command.toArray(String[]::new));
    }

    private List<String> commitCommand(String branch, String message) {
        requireBranch(branch);
        if (message == null || message.isBlank()) throw new IllegalArgumentException("commit message is required");
        return new ArrayList<>(List.of("-C", root.toString(), "commit", "-b", branch, "-m", message));
    }

    /** Replaces a commit message, restacking any dependent commits ({@code but reword}). */
    public void rewordCommit(String commit, String message, CancelToken cancel) {
        if (commit == null || commit.isBlank()) throw new IllegalArgumentException("commit id is required");
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("commit message is required");
        }
        runner.run(cancel, "-C", root.toString(), "reword", commit, "-m", message);
    }

    /** Moves a commit to the tip of another applied branch ({@code but move --branch}). */
    public void moveCommitToBranch(String commit, String branch, CancelToken cancel) {
        if (commit == null || commit.isBlank()) throw new IllegalArgumentException("commit id is required");
        requireBranch(branch);
        runner.run(cancel, "-C", root.toString(), "move", commit, "--branch", branch);
    }

    /** Moves commits above or below another commit, also across branches ({@code but move --above/--below}). */
    public void moveCommits(List<String> commits, String target, boolean above, CancelToken cancel) {
        List<String> ids = ids(commits);
        if (target == null || target.isBlank()) throw new IllegalArgumentException("target commit is required");
        if (ids.contains(target)) throw new IllegalArgumentException("a commit cannot be moved onto itself");
        List<String> command = new ArrayList<>(List.of("-C", root.toString(), "move"));
        command.addAll(ids);
        command.addAll(List.of(above ? "--above" : "--below", target));
        runner.run(cancel, command.toArray(String[]::new));
    }

    /** Moves commits to the tip of an applied branch ({@code but move --branch}). */
    public void moveCommitsToBranch(List<String> commits, String branch, CancelToken cancel) {
        List<String> ids = ids(commits);
        requireBranch(branch);
        List<String> command = new ArrayList<>(List.of("-C", root.toString(), "move"));
        command.addAll(ids);
        command.addAll(List.of("--branch", branch));
        runner.run(cancel, command.toArray(String[]::new));
    }

    /** Makes a stacked branch independent again ({@code but move --unstack}). */
    public void unstackBranch(String branch, CancelToken cancel) {
        requireBranch(branch);
        runner.run(cancel, "-C", root.toString(), "move", branch, "--unstack");
    }

    /** Amends uncommitted files into a commit ({@code but amend -t}). */
    public void amendChanges(List<String> paths, String commit, CancelToken cancel) {
        List<String> files = ids(paths);
        if (commit == null || commit.isBlank()) throw new IllegalArgumentException("commit id is required");
        List<String> command = new ArrayList<>(List.of("-C", root.toString(), "amend", "-t", commit));
        command.addAll(files);
        runner.run(cancel, command.toArray(String[]::new));
    }

    /** Squashes commits into a target commit, keeping the target's message ({@code but squash -u}). */
    public void squashCommits(List<String> sources, String target, CancelToken cancel) {
        List<String> ids = ids(sources);
        if (target == null || target.isBlank()) throw new IllegalArgumentException("target commit is required");
        if (ids.contains(target)) throw new IllegalArgumentException("a commit cannot be squashed into itself");
        List<String> command = new ArrayList<>(List.of("-C", root.toString(), "squash"));
        command.addAll(ids);
        command.addAll(List.of("-t", target, "-u"));
        runner.run(cancel, command.toArray(String[]::new));
    }

    private static List<String> ids(List<String> values) {
        List<String> out = values == null ? List.of() : values.stream().filter(v -> v != null && !v.isBlank()).toList();
        if (out.isEmpty()) throw new IllegalArgumentException("at least one id is required");
        return out;
    }

    /** Discards the uncommitted changes at {@code path} ({@code but discard}). */
    public void discardChange(String path, CancelToken cancel) {
        discardChanges(List.of(path == null ? "" : path), cancel);
    }

    /** Discards the selected uncommitted changes ({@code but discard}). */
    public void discardChanges(List<String> paths, CancelToken cancel) {
        List<String> selected = paths == null ? List.of() : paths.stream()
                .filter(path -> path != null && !path.isBlank()).toList();
        if (selected.isEmpty()) throw new IllegalArgumentException("change path is required");
        List<String> command = new ArrayList<>(List.of("-C", root.toString(), "discard"));
        command.addAll(selected);
        runner.run(cancel, command.toArray(String[]::new));
    }

    /** Moves a commit back to the uncommitted changes ({@code but uncommit}). */
    public void uncommit(String commit, CancelToken cancel) {
        if (commit == null || commit.isBlank()) throw new IllegalArgumentException("commit id is required");
        runner.run(cancel, "-C", root.toString(), "uncommit", commit);
    }

    /** Checks whether fetching the target would conflict with applied branches ({@code but pull --check}). */
    public ButPullCheck checkPullWorkspace(CancelToken cancel) {
        String json = runner.runStdout(cancel, "-C", root.toString(), "pull", "--check", "--json");
        if (json.isBlank()) throw new IllegalStateException("but pull --check returned no JSON");
        return ButPullCheck.parse(json);
    }

    /** Fetches the latest target and rebases applied branches onto it ({@code but pull}). */
    public void pullWorkspace(CancelToken cancel) {
        runner.run(cancel, "-C", root.toString(), "pull");
    }

    /** Lists the conflicts of the oldest conflicted commit ({@code but resolve conflicts --json}). */
    public ButConflicts conflicts(CancelToken cancel) {
        String json = runner.runStdout(cancel, "-C", root.toString(), "resolve", "conflicts", "--json");
        if (json.isBlank()) throw new IllegalStateException("but resolve conflicts returned no JSON");
        return ButConflicts.parse(json);
    }

    /** Resolves one numbered hunk of a file by taking the remote side or the local commit side. */
    public void resolveConflictHunk(String branch, String path, int conflict, boolean ours,
                                    CancelToken cancel) {
        if (branch == null || branch.isBlank()) throw new IllegalArgumentException("branch name is required");
        if (path == null || path.isBlank()) throw new IllegalArgumentException("change path is required");
        if (conflict < 1) throw new IllegalArgumentException("conflict number must be positive");
        runner.run(cancel, "-C", root.toString(), "resolve", "apply", path + ":" + conflict,
                "--commit", branch, ours ? "--ours" : "--theirs");
    }

    /** Pushes a branch or its stack to the remote ({@code but push}). */
    public void pushBranch(String branch, CancelToken cancel) {
        pushStack(branch, cancel);
    }

    /** Pushes the stack containing {@code branch}, including all unpushed commits ({@code but push}). */
    public void pushStack(String branch, CancelToken cancel) {
        requireBranch(branch);
        runner.run(cancel, "-C", root.toString(), "push", branch);
    }

    /** Integrates the remote counterpart into a local branch ({@code but branch update}). */
    public void updateBranch(String branch, CancelToken cancel) {
        requireBranch(branch);
        runner.run(cancel, "-C", root.toString(), "branch", "update", branch);
    }

    private static void requireBranch(String branch) {
        if (branch == null || branch.isBlank()) throw new IllegalArgumentException("branch name is required");
    }

    /** Repository-relative diff as JSON ({@code but --json diff}). */
    public String diffJson() {
        return diffJson(null);
    }

    /** Cancellable variant of {@link #diffJson()}. */
    public String diffJson(CancelToken cancel) {
        String json = runner.runStdout(cancel, "--json", "-C", root.toString(), "diff");
        if (json.isBlank()) throw new IllegalStateException("but diff returned no JSON");
        return json;
    }

    /**
     * Locates but: explicit path, GITGUI_BUT/BUT_PATH, PATH, then the common GitButler and cargo install folders.
     *
     * @param pathEnv value of PATH; a parameter so the search can be tested without touching the environment
     */
    static String locate(String override, String pathEnv) {
        String exe = ExecutableFinder.find(override, pathEnv, List.of("but.exe", "but"),
                List.of("GITGUI_BUT", "BUT_PATH"), commonPaths());
        if (exe == null && override != null && !override.isBlank()) {
            throw new IllegalStateException("but executable not found: " + override);
        }
        return exe;
    }

    private static List<Path> commonPaths() {
        List<Path> paths = new ArrayList<>();
        String pf = System.getenv("ProgramFiles");
        String local = System.getenv("LOCALAPPDATA");
        String home = System.getenv("USERPROFILE");
        if (home == null || home.isBlank()) home = System.getProperty("user.home");
        if (pf != null) paths.add(Path.of(pf, "GitButler", "but.exe"));
        if (local != null) {
            paths.add(Path.of(local, "Programs", "GitButler", "but.exe"));
            paths.add(Path.of(local, "GitButler", "but.exe"));
        }
        if (home != null && !home.isBlank()) {
            paths.add(Path.of(home, ".cargo", "bin", "but.exe"));
            paths.add(Path.of(home, ".cargo", "bin", "but"));
            paths.add(Path.of(home, ".local", "bin", "but"));
        }
        paths.add(Path.of("/opt/homebrew/bin/but"));
        paths.add(Path.of("/usr/local/bin/but"));
        paths.add(Path.of("/usr/bin/but"));
        return paths;
    }
}
