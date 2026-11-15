package com.github.gitguiorg.gitgui.model;

/** One entry of {@code git status --porcelain=v2}. States use git's letters ('.' = unmodified). */
public record StatusEntry(Kind kind, char indexState, char worktreeState, String path, String origPath) {
    public enum Kind { ORDINARY, RENAMED, UNMERGED, UNTRACKED, IGNORED }

    public boolean isConflicted() {
        return kind == Kind.UNMERGED;
    }

    public boolean isStaged() {
        return (kind == Kind.ORDINARY || kind == Kind.RENAMED) && indexState != '.';
    }

    public boolean isUnstaged() {
        return kind == Kind.UNTRACKED || ((kind == Kind.ORDINARY || kind == Kind.RENAMED) && worktreeState != '.');
    }
}
