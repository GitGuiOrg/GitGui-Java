package com.github.gitguiorg.gitgui.model;

import java.util.List;

/** Commits an interactive rebase would replay (oldest first), starting at {@code from}. */
public record RebasePlan(Commit from, List<Commit> commits, boolean onRemote) {}
