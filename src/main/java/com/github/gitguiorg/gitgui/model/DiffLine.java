package com.github.gitguiorg.gitgui.model;

public record DiffLine(Type type, String text) {
    public enum Type {
        CONTEXT(' '), ADDED('+'), REMOVED('-'), NO_NEWLINE('\\');
        public final char prefix;
        Type(char prefix) { this.prefix = prefix; }
    }

    public String render() {
        return type.prefix + text;
    }
}
