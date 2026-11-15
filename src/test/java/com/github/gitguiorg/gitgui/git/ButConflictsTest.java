package com.github.gitguiorg.gitgui.git;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ButConflictsTest {
    @Test
    void readsFilesHunksAndBothSides() {
        ButConflicts conflicts = ButConflicts.parse("""
                {
                  "commit_id": "9b66dd8fbb8dd6767aad662d3fa8665ec190dee7",
                  "branch": "feature",
                  "files": [
                    {
                      "path": "feature.txt",
                      "hunks": [
                        {
                          "line": 1,
                          "ours": "remote feature",
                          "base": "",
                          "theirs": "local feature"
                        },
                        {
                          "line": 20,
                          "ours": "remote ending",
                          "base": "ending",
                          "theirs": "local ending"
                        }
                      ]
                    }
                  ],
                  "manual": []
                }
                """);

        assertThat(conflicts.commitId()).isEqualTo("9b66dd8fbb8dd6767aad662d3fa8665ec190dee7");
        assertThat(conflicts.branch()).isEqualTo("feature");
        assertThat(conflicts.files()).singleElement().satisfies(file -> {
            assertThat(file.path()).isEqualTo("feature.txt");
            assertThat(file.hunks()).hasSize(2);
            assertThat(file.hunks().get(0).line()).isEqualTo(1);
            assertThat(file.hunks().get(0).ours()).isEqualTo("remote feature");
            assertThat(file.hunks().get(0).theirs()).isEqualTo("local feature");
            assertThat(file.hunks().get(1).line()).isEqualTo(20);
            assertThat(file.hunks().get(0).preview())
                    .contains("Remote: remote feature", "Local: local feature");
            assertThat(file.preview()).contains("Remote: remote feature", "Local: local feature");
        });
        assertThat(conflicts.manual()).isZero();
        assertThat(conflicts.isEmpty()).isFalse();
    }

    @Test
    void countsManualConflictsAndToleratesEmptyFiles() {
        ButConflicts conflicts = ButConflicts.parse("""
                {"branch": "feature", "files": [], "manual": [{"path": "binary.dat"}]}
                """);

        assertThat(conflicts.isEmpty()).isTrue();
        assertThat(conflicts.manual()).isEqualTo(1);
    }
}
