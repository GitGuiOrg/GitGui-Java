package com.github.gitguiorg.gitgui.git;

import java.time.Instant;

/** A GitButler branch that is not currently applied to the workspace. */
public record ButBranch(String name, boolean hasLocal, Integer commitsAhead, Instant lastCommitAt,
                        String lastAuthor, boolean mergesCleanly) {
}
