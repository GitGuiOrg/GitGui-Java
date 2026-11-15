package com.github.gitguiorg.gitgui;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.gitguiorg.gitgui.git.GitClient;
import com.github.gitguiorg.gitgui.git.GitCommand;
import com.github.gitguiorg.gitgui.git.GitRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HooksTest {
    @TempDir Path tmp;
    GitRunner runner = new GitRunner(null);
    Path dir;
    GitClient git;

    @BeforeEach
    void setUp() throws Exception {
        dir = tmp.resolve("repo");
        git = GitClient.init(dir, runner);
        for (String[] kv : new String[][]{{"user.name", "T"}, {"user.email", "t@x.it"}, {"commit.gpgsign", "false"}}) {
            runner.run(GitCommand.in(dir).args("config", kv[0], kv[1]));
        }
        Files.writeString(dir.resolve("a.txt"), "a\n");
        git.stage(List.of("a.txt"));
        git.commit("base", false);
    }

    @Test
    void hookIsFoundInTheMainWorktree() throws Exception {
        assertThat(git.commitHookPresent()).isFalse();
        Files.writeString(dir.resolve(".git/hooks/pre-commit"), "#!/bin/sh\nexit 1\n");
        assertThat(git.commitHookPresent()).isTrue();
    }

    @Test
    void linkedWorktreeUsesTheHooksOfTheCommonDirectory() throws Exception {
        Files.writeString(dir.resolve(".git/hooks/commit-msg"), "#!/bin/sh\nexit 1\n");
        Path wt = tmp.resolve("linked");
        git.worktreeAdd(wt, "side", "HEAD");

        assertThat(GitClient.open(wt, runner).commitHookPresent()).isTrue();
    }

    @Test
    void configuredRelativeHooksPathIsResolvedFromTheRepositoryRoot() throws Exception {
        runner.run(GitCommand.in(dir).args("config", "core.hooksPath", "my-hooks"));
        Files.createDirectories(dir.resolve("my-hooks"));
        assertThat(git.commitHookPresent()).isFalse();
        Files.writeString(dir.resolve("my-hooks/pre-commit"), "#!/bin/sh\nexit 1\n");
        assertThat(git.commitHookPresent()).isTrue();
    }
}
