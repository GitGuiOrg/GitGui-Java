package com.github.gitguiorg.gitgui.git;

import java.time.Duration;

/** Maintenance commands of a {@link GitClient}: they can be slow, so they get their own timeout. */
final class GitMaintenance {
    private static final Duration TIMEOUT = Duration.ofMinutes(10);

    private final GitClient git;

    GitMaintenance(GitClient git) {
        this.git = git;
    }

    /** Packs loose objects and drops unreachable ones ({@code git gc}). */
    void gc() {
        git.write(git.cmd("gc"), TIMEOUT);
    }

    /** Prunes unreachable loose objects ({@code git prune}). */
    void prune() {
        git.write(git.cmd("prune"), TIMEOUT);
    }

    /**
     * Checks connectivity and object validity ({@code git fsck}).
     *
     * @return the command output, or "No problems found." when fsck printed nothing
     */
    String fsck() {
        GitResult r = git.read(git.cmd("fsck", "--no-progress").allowExit(0, 1, 2), TIMEOUT);
        String out = r.out().strip();
        if (r.exitCode() == 0) return out.isEmpty() ? "No problems found." : out;
        String err = r.stderr().strip();
        StringBuilder sb = new StringBuilder("exit ").append(r.exitCode());
        if (!out.isEmpty()) sb.append('\n').append(out);
        if (!err.isEmpty()) sb.append('\n').append(err);
        return sb.toString();
    }
}
