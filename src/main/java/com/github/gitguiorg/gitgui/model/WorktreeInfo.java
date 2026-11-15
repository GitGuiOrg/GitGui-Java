package com.github.gitguiorg.gitgui.model;

import com.github.gitguiorg.gitgui.util.Hashes;

/** One entry of {@code git worktree list}. {@code branch} is null for a detached HEAD. */
public record WorktreeInfo(String path, String head, String branch, boolean main, boolean bare, boolean locked,
                           boolean prunable) {
    public String label() {
        String what = bare ? "bare" : branch != null ? branch : "detached @ " + (head == null ? "?" : Hashes.shortHash(head));
        return path + "  [" + what + (locked ? ", locked" : "") + "]";
    }
}
