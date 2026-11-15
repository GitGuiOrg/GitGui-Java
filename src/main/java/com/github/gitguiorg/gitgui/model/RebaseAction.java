package com.github.gitguiorg.gitgui.model;

public enum RebaseAction {
    PICK("pick", "Pick"), REWORD("pick", "Reword"), SQUASH("squash", "Squash"), FIXUP("fixup", "Fixup"), DROP("drop", "Drop");

    /** Keyword written in the git todo list (reword is a pick followed by an amend). */
    public final String todoKeyword;
    public final String label;

    RebaseAction(String todoKeyword, String label) {
        this.todoKeyword = todoKeyword;
        this.label = label;
    }

    @Override
    public String toString() { return label; }
}
