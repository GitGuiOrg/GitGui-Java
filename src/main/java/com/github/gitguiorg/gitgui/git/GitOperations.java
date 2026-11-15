package com.github.gitguiorg.gitgui.git;

import com.github.gitguiorg.gitgui.model.MergeOutcome;
import com.github.gitguiorg.gitgui.model.Operation;
import java.nio.file.Files;
import java.nio.file.Path;

/** Multi-step operation state and continuation of a {@link GitClient}. */
final class GitOperations {
    private final GitClient git;
    private final GitRebase rebase;

    private Path gitDir;
    private Path commonDir;
    private final Object commonDirLock = new Object();

    GitOperations(GitClient git, GitRebase rebase) {
        this.git = git;
        this.rebase = rebase;
    }

    private synchronized Path gitDir() {
        if (gitDir == null) {
            gitDir = Path.of(git.read(git.cmd("rev-parse", "--absolute-git-dir")).out().strip());
        }
        return gitDir;
    }

    /** Administrative git directory of this checkout; in a linked worktree it is under the main {@code .git}. */
    Path gitDirPath() {
        return gitDir();
    }

    /** Shared git directory that holds refs and objects; differs from {@link #gitDirPath()} in a linked worktree. */
    Path commonDirPath() {
        synchronized (commonDirLock) {
            if (commonDir == null) {
                String out = git.read(git.cmd("rev-parse", "--git-common-dir")).out().strip();
                commonDir = git.root().resolve(out).toAbsolutePath().normalize();
            }
            return commonDir;
        }
    }

    /** The multi-step operation currently in progress, if any. */
    Operation operation() {
        Path g = gitDir();
        // an "am" in progress keeps a rebase-apply directory with the applying marker; plain rebase does not
        if (Files.exists(g.resolve("rebase-apply").resolve("applying"))) return Operation.AM;
        if (Files.exists(g.resolve("rebase-merge")) || Files.exists(g.resolve("rebase-apply"))) return Operation.REBASE;
        if (Files.isRegularFile(g.resolve("BISECT_LOG"))) return Operation.BISECT;
        if (Files.exists(g.resolve("MERGE_HEAD"))) return Operation.MERGE;
        if (Files.exists(g.resolve("CHERRY_PICK_HEAD"))) return Operation.CHERRY_PICK;
        if (Files.exists(g.resolve("REVERT_HEAD"))) return Operation.REVERT;
        return Operation.NONE;
    }

    /** Short name of the branch that receives the rebased commits, or null when no rebase is in progress. */
    String rebaseBranch() {
        Path g = gitDir();
        Path f = Files.isRegularFile(g.resolve("rebase-merge").resolve("head-name"))
                ? g.resolve("rebase-merge").resolve("head-name")
                : g.resolve("rebase-apply").resolve("head-name");
        try {
            String s = Files.readString(f).strip();
            return s.startsWith("refs/heads/") ? s.substring("refs/heads/".length()) : s;
        } catch (java.io.IOException e) {
            return null;
        }
    }

    /** Short hash and subject of the commit a rebase is currently applying, or null. */
    String rebaseIncoming() {
        try {
            String s = git.read(git.cmd("log", "-1", "--format=%h %s", "REBASE_HEAD")).out().strip();
            return s.isEmpty() ? null : s;
        } catch (GitException e) {
            return null;
        }
    }

    /** First line of MERGE_MSG while a merge is in progress, else null. */
    String operationMessage() {
        try {
            Path f = gitDir().resolve("MERGE_MSG");
            if (!Files.isRegularFile(f)) return null;
            return Files.readAllLines(f).stream()
                    .filter(s -> !s.isBlank() && !s.startsWith("#"))
                    .findFirst()
                    .orElse(null);
        } catch (java.io.IOException e) {
            return null;
        }
    }

    MergeOutcome operationContinue(Operation op) {
        return switch (op) {
            case MERGE -> git.conflictAware(git.cmd("commit", "--no-edit"));
            case CHERRY_PICK -> git.conflictAware(git.cmd("cherry-pick", "--continue"));
            case REVERT -> git.conflictAware(git.cmd("revert", "--continue"));
            case AM -> git.conflictAware(git.cmd("am", "--continue"));
            case REBASE -> {
                MergeOutcome o = git.conflictAware(git.cmd("rebase", "--continue"));
                if (o == MergeOutcome.DONE) rebase.cleanWork();
                yield o;
            }
            case BISECT -> {
                // "continue" in the banner means: the checked-out candidate is good
                git.write(git.cmd("bisect", "good", "HEAD"));
                yield MergeOutcome.DONE;
            }
            case NONE -> MergeOutcome.DONE;
        };
    }

    void operationAbort(Operation op) {
        switch (op) {
            case MERGE -> git.write(git.cmd("merge", "--abort"));
            case CHERRY_PICK -> git.write(git.cmd("cherry-pick", "--abort"));
            case REVERT -> git.write(git.cmd("revert", "--abort"));
            case AM -> git.write(git.cmd("am", "--abort"));
            case REBASE -> {
                git.write(git.cmd("rebase", "--abort"));
                rebase.cleanWork();
            }
            case BISECT -> git.write(git.cmd("bisect", "reset"));
            case NONE -> { }
        }
    }
}
