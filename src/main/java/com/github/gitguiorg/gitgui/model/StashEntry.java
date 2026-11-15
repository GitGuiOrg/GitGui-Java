package com.github.gitguiorg.gitgui.model;

import java.time.Instant;

/** One entry of {@code git stash list}. {@code ref} is e.g. {@code stash@{0}}. */
public record StashEntry(int index, String ref, String hash, Instant date, String message) {}