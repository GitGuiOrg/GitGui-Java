package com.github.gitguiorg.gitgui.git;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ButPullCheckTest {
    @Test
    void parsesACleanCheck() {
        ButPullCheck check = ButPullCheck.parse("""
                {
                  "baseBranch": {"name": "origin/main"},
                  "upstreamCommits": {"count": 1},
                  "branchStatuses": [{"name": "feature", "status": "ok", "rebasable": true}],
                  "upToDate": false,
                  "hasWorktreeConflicts": false
                }
                """);

        assertThat(check.baseBranch()).isEqualTo("origin/main");
        assertThat(check.upstreamCommits()).isEqualTo(1);
        assertThat(check.branches()).singleElement()
                .satisfies(branch -> assertThat(branch.status()).isEqualTo("ok"));
        assertThat(check.hasConflicts()).isFalse();
        assertThat(check.summary()).contains("origin/main", "feature: ok");
    }

    @Test
    void detectsAConflictedBranch() {
        ButPullCheck check = ButPullCheck.parse("""
                {
                  "baseBranch": {"name": "origin/main"},
                  "upstreamCommits": {"count": 2},
                  "branchStatuses": [{"name": "feature", "status": "conflicted", "rebasable": true}],
                  "upToDate": false,
                  "hasWorktreeConflicts": false
                }
                """);

        assertThat(check.hasConflicts()).isTrue();
        assertThat(check.summary()).contains("2 new commits", "conflicted", "rebasable");
    }

    @Test
    void reportsAnUpToDateWorkspace() {
        ButPullCheck check = ButPullCheck.parse("""
                {
                  "upstreamCommits": {"count": 0},
                  "upToDate": true
                }
                """);

        assertThat(check.upstreamCommits()).isZero();
        assertThat(check.upToDate()).isTrue();
        assertThat(check.summary()).isEqualTo("Already up to date.");
    }
}
