package com.github.gitguiorg.gitgui.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ButBranchesTest {
    private static final String SAMPLE = """
            {
              "appliedStacks": [],
              "branches": [
                {
                  "name": "feature-two",
                  "reviews": [],
                  "hasLocal": true,
                  "lastCommitAt": 1794490462000,
                  "commitsAhead": 1,
                  "lastAuthor": {"name": "Test"},
                  "mergesCleanly": true
                }
              ],
              "hasMoreBranches": false
            }
            """;

    @Test
    void readsUnappliedBranches() {
        List<ButBranch> branches = ButBranches.parse(SAMPLE);

        assertThat(branches).hasSize(1);
        ButBranch branch = branches.get(0);
        assertThat(branch.name()).isEqualTo("feature-two");
        assertThat(branch.hasLocal()).isTrue();
        assertThat(branch.commitsAhead()).isEqualTo(1);
        assertThat(branch.lastCommitAt()).isEqualTo(Instant.ofEpochMilli(1794490462000L));
        assertThat(branch.lastAuthor()).isEqualTo("Test");
        assertThat(branch.mergesCleanly()).isTrue();
    }

    @Test
    void toleratesMissingBranchesAndOptionalFields() {
        assertThat(ButBranches.parse("{}")).isEmpty();
        List<ButBranch> branches = ButBranches.parse("{\"branches\":[{\"name\":\"only-name\"}]}");
        assertThat(branches).hasSize(1);
        assertThat(branches.get(0).commitsAhead()).isNull();
        assertThat(branches.get(0).lastCommitAt()).isNull();
        assertThat(branches.get(0).lastAuthor()).isEmpty();
        assertThat(branches.get(0).mergesCleanly()).isTrue();
    }

    @Test
    void rejectsOutputThatIsNotJson() {
        assertThatThrownBy(() -> ButBranches.parse("Initiated a background sync..."))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid but branch JSON");
    }
}
