package com.github.gitguiorg.gitgui.git;

import com.github.gitguiorg.gitgui.model.GitProgress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Fetch, pull and push of a {@link GitClient}. */
final class GitNetwork {
    private static final Duration NETWORK_TIMEOUT = Duration.ofMinutes(10);

    private final GitClient git;

    GitNetwork(GitClient git) {
        this.git = git;
    }

    void fetch(String remote, boolean prune, boolean interactive,
               Consumer<GitProgress> progress, CancelToken cancel) {
        List<String> a = new ArrayList<>(List.of("fetch", "--progress"));
        if (prune) a.add("--prune");
        if (remote == null) a.add("--all"); else a.add(remote);
        run(git.cmd().args(a), progress, cancel, false, interactive);
    }

    void pull(GitClient.PullMode mode, Consumer<GitProgress> progress, CancelToken cancel) {
        String flag = switch (mode) {
            case MERGE -> "--no-rebase";
            case REBASE -> "--rebase";
            case FF_ONLY -> "--ff-only";
        };
        run(git.cmd("pull", "--progress", flag), progress, cancel, true);
    }

    void push(String remote, String branch, boolean setUpstream, boolean forceWithLease,
              Consumer<GitProgress> progress, CancelToken cancel) {
        List<String> a = new ArrayList<>(List.of("push", "--progress"));
        if (setUpstream) a.add("-u");
        if (forceWithLease) a.add("--force-with-lease");
        if (remote != null) a.add(remote);
        if (branch != null) a.add(branch);
        run(git.cmd().args(a), progress, cancel, false);
    }

    /** @param exclusive take the repo write lock (pull touches the working tree); fetch/push must not block reads */
    void run(GitCommand c, Consumer<GitProgress> progress, CancelToken cancel, boolean exclusive) {
        run(c, progress, cancel, exclusive, true);
    }

    void run(GitCommand c, Consumer<GitProgress> progress, CancelToken cancel, boolean exclusive, boolean interactive) {
        if (interactive) c.interactive();
        if (cancel != null) c.cancel(cancel);
        if (progress != null) {
            c.onStderrLine(line -> ProgressParser.parse(line).ifPresent(progress));
        }
        if (exclusive) git.write(c, NETWORK_TIMEOUT); else git.runUnlocked(c, NETWORK_TIMEOUT);
    }
}
