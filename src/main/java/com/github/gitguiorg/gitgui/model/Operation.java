package com.github.gitguiorg.gitgui.model;

/** A multi-step git operation that may be waiting for conflicts to be resolved. */
public enum Operation {
    NONE("", ""), MERGE("Merge", "Commit merge"), CHERRY_PICK("Cherry-pick", "Continue"),
    REVERT("Revert", "Continue"), REBASE("Rebase", "Continue"), AM("Patch apply", "Continue"),
    BISECT("Bisect", "Good");

    public final String title;
    public final String continueLabel;

    Operation(String title, String continueLabel) {
        this.title = title;
        this.continueLabel = continueLabel;
    }
}
