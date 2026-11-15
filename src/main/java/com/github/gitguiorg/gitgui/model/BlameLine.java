package com.github.gitguiorg.gitgui.model;

import java.time.Instant;

/** One annotated line of {@code git blame}. {@code line} is the 1-based line number in the blamed version. */
public record BlameLine(int line, String hash, String author, Instant date, String summary, String text) {
    public boolean uncommitted() {
        return hash.chars().allMatch(c -> c == '0');
    }
}
