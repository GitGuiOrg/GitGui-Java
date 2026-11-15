package com.github.gitguiorg.gitgui.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RemoteUrlsTest {
    @Test
    void repoNameMatchesGitChoice() {
        assertThat(RemoteUrls.repoName("https://github.com/a/b.git")).isEqualTo("b");
        assertThat(RemoteUrls.repoName("https://github.com/a/b/")).isEqualTo("b");
        assertThat(RemoteUrls.repoName("git@github.com:a/b.git")).isEqualTo("b");
        assertThat(RemoteUrls.repoName("ssh://git@host:2222/a/b.git")).isEqualTo("b");
        assertThat(RemoteUrls.repoName("C:\\repos\\my-repo\\")).isEqualTo("my-repo");
        assertThat(RemoteUrls.repoName("file:///tmp/r.git")).isEqualTo("r");
        assertThat(RemoteUrls.repoName("  ")).isEmpty();
        assertThat(RemoteUrls.repoName(null)).isEmpty();
    }

    @Test
    void displayHidesTokensInHttpUrls() {
        assertThat(RemoteUrls.display("https://token@github.com/a/b.git"))
                .isEqualTo("https://***@github.com/a/b.git");
        assertThat(RemoteUrls.display("https://user:secret@host/a.git"))
                .isEqualTo("https://***@host/a.git");
        assertThat(RemoteUrls.display("  https://host/a.git  ")).isEqualTo("https://host/a.git");
    }

    @Test
    void displayKeepsSshUsersAndLocalPaths() {
        assertThat(RemoteUrls.display("ssh://git@host/a/b.git")).isEqualTo("ssh://git@host/a/b.git");
        assertThat(RemoteUrls.display("git@host:a/b.git")).isEqualTo("git@host:a/b.git");
        assertThat(RemoteUrls.display("C:\\repos\\a@b")).isEqualTo("C:\\repos\\a@b");
        assertThat(RemoteUrls.display("https://host/a@b")).isEqualTo("https://host/a@b");
        assertThat(RemoteUrls.display(null)).isEmpty();
    }
}
