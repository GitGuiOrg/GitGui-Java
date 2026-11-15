package com.github.gitguiorg.gitgui.git;

import com.github.gitguiorg.gitgui.model.Operation;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** Bisect operations of a {@link GitClient}. */
final class GitBisect {
    private final GitClient git;

    GitBisect(GitClient git) {
        this.git = git;
    }

    GitClient.BisectState state() {
        if (!Files.isRegularFile(git.gitDirPath().resolve("BISECT_LOG"))) {
            return new GitClient.BisectState(null, List.of(), 0);
        }
        String bad = null;
        List<String> good = new ArrayList<>();
        for (String line : git.read(git.cmd("for-each-ref", "--format=%(refname) %(objectname)", "refs/bisect")).out().split("\n")) {
            String[] f = line.strip().split(" ", 2);
            if (f.length < 2) continue;
            if (f[0].equals("refs/bisect/bad")) bad = f[1].strip();
            else if (f[0].startsWith("refs/bisect/good-")) good.add(f[1].strip());
        }
        int candidates = 0;
        if (bad != null) {
            List<String> a = new ArrayList<>(List.of("rev-list", "--count", bad));
            for (String g : good) a.add("^" + g);
            GitResult r = git.read(git.cmd().args(a).allowExit(1));
            if (r.exitCode() == 0) {
                try {
                    candidates = Integer.parseInt(r.out().strip());
                } catch (NumberFormatException ignored) {
                    candidates = 0;
                }
            }
        }
        return new GitClient.BisectState(bad, good, candidates);
    }

    /** Starts a bisect when needed, then marks {@code rev} as good or bad. */
    void mark(String rev, boolean good) {
        if (git.operation() != Operation.BISECT) git.write(git.cmd("bisect", "start"));
        git.write(git.cmd("bisect", good ? "good" : "bad", rev));
    }

    void skip() {
        git.write(git.cmd("bisect", "skip"));
    }

    void reset() {
        git.write(git.cmd("bisect", "reset"));
    }
}
