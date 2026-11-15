package com.github.gitguiorg.gitgui.git;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GitVersionTest {
    @Test
    void parsesMajorAndMinor() {
        assertThat(GitClient.parseVersion("git version 2.32.0.windows.1")).containsExactly(2, 32);
        assertThat(GitClient.parseVersion("git version 2.43.1")).containsExactly(2, 43);
        assertThat(GitClient.parseVersion("git version 3.0.0")).containsExactly(3, 0);
    }

    @Test
    void unparsableVersionIsNullOrAssumedRecent() {
        assertThat(GitClient.parseVersion(null)).isNull();
        assertThat(GitClient.parseVersion("git")).isNull();
        assertThat(GitClient.parseVersion("")).isNull();
    }
}
