package com.github.gitguiorg.gitgui.model;

import java.time.Instant;

/** One line of {@code git reflog}: a commit plus the operation that moved the ref there. */
public record ReflogEntry(String hash, String shortHash, String selector, String action, String message, Instant date) {
    /** {@code action} and {@code message} joined, e.g. {@code reset: moving to HEAD~1}. */
    public String summary() {
        return message == null || message.isBlank() ? action : action + ": " + message;
    }
}
