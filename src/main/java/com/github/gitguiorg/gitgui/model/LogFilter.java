package com.github.gitguiorg.gitgui.model;

/**
 * Filter of the commit history. {@code branch} null means all refs, "HEAD" the current branch.
 * A text filter makes the result non-contiguous, so the graph is not drawn ({@link #flat()}).
 */
public record LogFilter(Mode mode, String text, String branch) {
    public enum Mode {
        MESSAGE("Message"), AUTHOR("Author"), FILE("File"), HASH("Hash"), CONTENT("Content");

        public final String label;

        Mode(String label) { this.label = label; }
    }

    public static final LogFilter NONE = new LogFilter(Mode.MESSAGE, "", null);

    public boolean hasText() { return text != null && !text.isBlank(); }
    public boolean isActive() { return hasText() || branch != null; }
    public boolean flat() { return hasText(); }
}
