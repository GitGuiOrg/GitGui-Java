package com.github.gitguiorg.gitgui.model;

/** One entry of {@code git submodule status --recursive}. */
public record SubmoduleInfo(String path, String hash, State state, String describe, boolean dirty) {
    public SubmoduleInfo(String path, String hash, State state, String describe) {
        this(path, hash, state, describe, false);
    }

    public enum State {
        /** Not cloned/checked out yet. */ UNINITIALIZED,
        /** Checked out at the recorded commit. */ CLEAN,
        /** Checked out at another commit than the one recorded. */ MODIFIED,
        /** Merge conflicts in the submodule. */ CONFLICT
    }

    public String statusText() {
        return switch (state) {
            case UNINITIALIZED -> "not initialized";
            case CLEAN -> dirty ? "uncommitted changes" : "up to date";
            case MODIFIED -> "different commit";
            case CONFLICT -> "conflict";
        };
    }
}
