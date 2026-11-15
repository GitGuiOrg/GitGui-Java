package com.github.gitguiorg.gitgui.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class GitRefTest {
    private static final List<String> REMOTES = List.of("origin", "team/origin");

    @Test
    void remoteNamesContainingSlashAreKeptWhole() {
        assertThat(GitRef.remoteOf("team/origin/feature/x", REMOTES)).isEqualTo("team/origin");
        assertThat(GitRef.branchOf("team/origin/feature/x", "team/origin")).isEqualTo("feature/x");
        assertThat(GitRef.remoteOf("origin/main", REMOTES)).isEqualTo("origin");
        assertThat(GitRef.branchOf("origin/main", "origin")).isEqualTo("main");
    }

    @Test
    void longestConfiguredRemoteWins() {
        assertThat(GitRef.remoteOf("team/origin/main", List.of("team", "team/origin"))).isEqualTo("team/origin");
    }

    @Test
    void unknownRemoteFallsBackToFirstSegment() {
        assertThat(GitRef.remoteOf("other/main", REMOTES)).isEqualTo("other");
        assertThat(GitRef.branchOf("main", "origin")).isEqualTo("main");
    }
}
