package com.github.gitguiorg.gitgui.git;

import com.github.gitguiorg.gitgui.model.ReflogEntry;
import java.util.List;

/** Reflog queries of a {@link GitClient}. */
final class GitReflog {
    private final GitClient git;

    GitReflog(GitClient git) {
        this.git = git;
    }

    /**
     * Reflog of a ref, newest first.
     *
     * @param ref a branch, tag or revision; null or blank means HEAD
     */
    List<ReflogEntry> log(String ref, int limit) {
        GitCommand c = git.cmd("log", "-g", "--format=" + ReflogParser.FORMAT, "-n", Integer.toString(Math.max(1, limit)));
        if (ref != null && !ref.isBlank()) c.args(ref);
        return ReflogParser.parse(git.read(c).out());
    }
}
