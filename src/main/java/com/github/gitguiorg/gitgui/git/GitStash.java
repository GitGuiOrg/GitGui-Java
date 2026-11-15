package com.github.gitguiorg.gitgui.git;

import com.github.gitguiorg.gitgui.model.FileDiff;
import com.github.gitguiorg.gitgui.model.MergeOutcome;
import com.github.gitguiorg.gitgui.model.StashEntry;
import java.util.ArrayList;
import java.util.List;

/** Stash operations of a {@link GitClient}; keeps the facade free of the parsing details. */
final class GitStash {
    private final GitClient git;

    GitStash(GitClient git) {
        this.git = git;
    }

    List<StashEntry> list() {
        String out = git.read(git.cmd("stash", "list", "--format=%gd%x00%H%x00%at%x00%gs")).out();
        List<StashEntry> list = new ArrayList<>();
        for (String line : out.split("\n")) {
            if (line.isBlank()) continue;
            String[] f = line.split("\0", -1);
            if (f.length < 4) continue;
            int idx = Integer.parseInt(f[0].substring(f[0].indexOf('{') + 1, f[0].indexOf('}')));
            list.add(new StashEntry(idx, f[0], f[1], java.time.Instant.ofEpochSecond(Long.parseLong(f[2])), f[3]));
        }
        return list;
    }

    void push(String message, boolean includeUntracked) {
        List<String> a = new ArrayList<>(List.of("stash", "push"));
        if (includeUntracked) a.add("-u");
        if (message != null && !message.isBlank()) {
            a.add("-m");
            a.add(message);
        }
        git.write(git.cmd().args(a));
    }

    MergeOutcome apply(String ref) { return git.conflictAware(git.cmd("stash", "apply", ref)); }

    MergeOutcome pop(String ref) { return git.conflictAware(git.cmd("stash", "pop", ref)); }

    void drop(String ref) { git.write(git.cmd("stash", "drop", ref)); }

    /** Puts a dropped stash back, at the top of the list, from its commit hash. */
    void store(String hash, String message) {
        git.write(git.cmd("stash", "store", "-m", message == null ? "" : message, hash));
    }

    List<FileDiff> diff(String ref, GitClient.DiffOptions o) {
        List<String> a = new ArrayList<>(List.of("stash", "show", "-p", "--no-color"));
        if (git.atLeast(2, 32)) a.add("--include-untracked");   // older git rejects the option and fails the whole diff
        a.addAll(o.flags());
        a.add(ref);
        return DiffParser.parse(git.read(git.cmd().args(a)).stdout());
    }
}
