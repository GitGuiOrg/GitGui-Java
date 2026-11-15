package com.github.gitguiorg.gitgui.git;

import com.github.gitguiorg.gitgui.model.Commit;
import com.github.gitguiorg.gitgui.model.MergeOutcome;
import com.github.gitguiorg.gitgui.model.RebaseAction;
import com.github.gitguiorg.gitgui.model.RebaseStep;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Commit relationships and rebase operations of a {@link GitClient}. */
final class GitRebase {
    private final GitClient git;

    GitRebase(GitClient git) {
        this.git = git;
    }

    boolean isAncestorOfHead(String hash) {
        return git.readRaw(git.cmd("merge-base", "--is-ancestor", hash, "HEAD")).exitCode() == 0;
    }

    /** True when the commit's changes are already in HEAD, either as an ancestor or as an equivalent cherry-pick. */
    boolean isAlreadyInHead(String hash) {
        GitResult r = git.read(git.cmd("cherry", "HEAD", hash).allowExit(1, 129));
        if (r.exitCode() != 0) return false;
        return !Arrays.stream(r.out().split("\n")).anyMatch(l -> l.startsWith("+ "));
    }

    /** True when the commit is reachable from some remote-tracking branch (rewriting it needs a force push). */
    boolean isOnRemote(String hash) {
        return !git.read(git.cmd("branch", "-r", "--contains", hash)).out().isBlank();
    }

    /** Non-merge commits an interactive rebase starting at {@code from} would replay, oldest first. */
    List<Commit> rebaseCandidates(Commit from) {
        String range = from.parents().isEmpty() ? "HEAD" : from.parents().get(0) + "..HEAD";
        return LogParser.parse(git.read(git.cmd("log", "--reverse", "--no-merges", "--format=" + LogParser.FORMAT, range)).out());
    }

    /** Replays the current branch on top of {@code rev}. */
    MergeOutcome rebaseOnto(String rev) {
        return git.conflictAware(git.cmd("rebase", "--autostash", rev));
    }

    /**
     * Runs {@code git rebase -i} without opening an editor: the generated todo list replaces git's via
     * GIT_SEQUENCE_EDITOR, and rewords are done with an {@code exec git commit --amend -F} line.
     * A squash carrying a custom message becomes {@code fixup} plus the same amend: the changes are folded
     * into the group's commit as with squash, and the amend replaces git's combined message with that text.
     * Conflicts leave the rebase in progress.
     */
    MergeOutcome rebaseInteractive(Commit from, List<RebaseStep> steps) {
        Path work = git.gitDirPath().resolve("gitgui-rebase");
        try {
            Files.createDirectories(work);
            StringBuilder todo = new StringBuilder();
            int n = 0;
            for (RebaseStep s : steps) {
                boolean amendMessage = s.newMessage() != null
                        && (s.action() == RebaseAction.REWORD || s.action() == RebaseAction.SQUASH);
                // a squash with a custom message becomes a fixup: the amend below sets the group's message
                String keyword = s.action() == RebaseAction.SQUASH && amendMessage ? "fixup" : s.action().todoKeyword;
                todo.append(keyword).append(' ').append(s.hash()).append(' ')
                        .append(s.subject().replace('\n', ' ')).append('\n');
                if (amendMessage) {
                    Path msg = work.resolve("msg" + (n++) + ".txt");
                    Files.writeString(msg, s.newMessage());
                    todo.append("exec git commit --amend -q --allow-empty -F ").append(shellQuote(msg)).append('\n');
                }
            }
            Path todoFile = work.resolve("todo.txt");
            Files.writeString(todoFile, todo.toString());

            GitCommand c = git.cmd("rebase", "-i", "--autostash");
            if (from.parents().isEmpty()) c.args("--root"); else c.args(from.parents().get(0));
            c.env("GIT_SEQUENCE_EDITOR", "cp " + shellQuote(todoFile));
            MergeOutcome outcome;
            try {
                outcome = git.conflictAware(c);
            } catch (RuntimeException e) {
                cleanWork();
                throw e;
            }
            if (outcome == MergeOutcome.DONE) cleanWork();
            return outcome;
        } catch (java.io.IOException e) {
            throw new GitException("cannot prepare rebase: " + e.getMessage(), e);
        }
    }

    private static String shellQuote(Path p) {
        return "'" + p.toString().replace('\\', '/').replace("'", "'\\''") + "'";
    }

    void cleanWork() {
        try {
            Path w = git.gitDirPath().resolve("gitgui-rebase");
            if (!Files.isDirectory(w)) return;
            try (var s = Files.walk(w)) {
                s.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (java.io.IOException ignored) {
                        // best effort
                    }
                });
            }
        } catch (java.io.IOException ignored) {
            // best effort
        }
    }

    /** Squashes the commits from {@code from} to HEAD into one with {@code message}; needs at least two commits. */
    MergeOutcome squashFrom(Commit from, String message) {
        List<Commit> commits = rebaseCandidates(from);
        if (commits.size() < 2) {
            throw new IllegalStateException("There is nothing to squash: select a commit with later commits on top of it.");
        }
        List<RebaseStep> steps = new ArrayList<>();
        for (int i = 0; i < commits.size(); i++) {
            Commit c = commits.get(i);
            steps.add(new RebaseStep(i == 0 ? RebaseAction.PICK : RebaseAction.SQUASH, c.hash(), c.subject(),
                    i == commits.size() - 1 ? message : null));
        }
        return rebaseInteractive(from, steps);
    }
}
