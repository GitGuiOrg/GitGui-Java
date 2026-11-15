package com.github.gitguiorg.gitgui.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class ButStatusTest {
    private static final String SAMPLE = """
            {
              "uncommittedChanges": [
                {"cliId": "nk", "filePath": "a.txt", "changeType": "modified"},
                {"cliId": "pn", "filePath": "src/b.txt", "changeType": "added"}
              ],
              "stacks": [
                {
                  "cliId": "g0",
                  "assignedChanges": [],
                  "branches": [
                    {
                      "cliId": "br",
                      "name": "feature",
                      "commits": [
                        {
                          "cliId": "mxr",
                          "commitId": "73adbb6a0bfa54ba277a6e368d21b70dabe6c738",
                          "createdAt": "2026-11-12T11:32:44+00:00",
                          "message": "feature work",
                          "authorName": "Test",
                          "conflicted": false
                        }
                      ],
                      "upstreamCommits": [],
                      "branchStatus": "completelyUnpushed",
                      "futureBranchField": true
                    }
                  ]
                }
              ],
              "mergeBase": {
                "commitId": "c6adc5814b49673352f899a4fd179a11e1fe6ac4",
                "createdAt": "2026-11-12T11:32:28+00:00",
                "message": "init\\n",
                "authorName": "Test",
                "conflicted": null
              },
              "upstreamState": {"behind": 2},
              "futureRootField": {"a": 1}
            }
            """;

    @Test
    void readsChangesStacksCommitsAndBase() {
        ButStatus status = ButStatus.parse(SAMPLE);

        assertThat(status.uncommittedChanges()).hasSize(2);
        assertThat(status.uncommittedChanges().get(0).cliId()).isEqualTo("nk");
        assertThat(status.uncommittedChanges().get(1).path()).isEqualTo("src/b.txt");
        assertThat(status.uncommittedChanges().get(1).changeType()).isEqualTo("added");

        assertThat(status.stacks()).hasSize(1);
        ButStatus.Branch branch = status.stacks().get(0).branches().get(0);
        assertThat(branch.name()).isEqualTo("feature");
        assertThat(branch.status()).isEqualTo("completelyUnpushed");
        assertThat(branch.commits()).hasSize(1);
        assertThat(branch.commits().get(0).shortId()).isEqualTo("73adbb6");
        assertThat(branch.commits().get(0).subject()).isEqualTo("feature work");
        assertThat(branch.commits().get(0).authorName()).isEqualTo("Test");
        assertThat(branch.commits().get(0).createdAt()).isEqualTo(Instant.parse("2026-11-12T11:32:44Z"));
        assertThat(branch.commits().get(0).conflicted()).isFalse();

        assertThat(status.base().shortId()).isEqualTo("c6adc58");
        assertThat(status.base().subject()).isEqualTo("init");
        assertThat(status.behind()).isEqualTo(2);
    }

    @Test
    void countsConflictedCommits() {
        ButStatus status = ButStatus.parse("""
                {"stacks": [{"branches": [
                  {"name": "feature", "commits": [
                    {"commitId": "aaaaaaaa", "conflicted": true},
                    {"commitId": "bbbbbbbb", "conflicted": false},
                    {"commitId": "cccccccc", "conflicted": true}
                  ]}
                ]}]}
                """);

        assertThat(status.conflictedCommits()).isEqualTo(2);
    }

    @Test
    void toleratesAnEmptyWorkspace() {
        ButStatus status = ButStatus.parse("{}");

        assertThat(status.uncommittedChanges()).isEmpty();
        assertThat(status.stacks()).isEmpty();
        assertThat(status.base()).isNull();
        assertThat(status.behind()).isZero();
    }

    @Test
    void rejectsOutputThatIsNotJson() {
        assertThatThrownBy(() -> ButStatus.parse("Initiated a background sync..."))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid but status JSON");
    }
}
