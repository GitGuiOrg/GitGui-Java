package com.github.gitguiorg.gitgui;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.gitguiorg.gitgui.git.GitClient;
import com.github.gitguiorg.gitgui.git.GitCommand;
import com.github.gitguiorg.gitgui.git.GitRunner;
import com.github.gitguiorg.gitgui.service.RepoSession;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WatcherTest {
    @TempDir Path tmp;
    GitRunner runner = new GitRunner(null);

    @Test
    void linkedWorktreeSeesChangesMadeOnlyInItsGitDirectory() throws Exception {
        Path main = tmp.resolve("main");
        GitClient git = GitClient.init(main, runner);
        for (String[] kv : new String[][]{{"user.name", "T"}, {"user.email", "t@x.it"}, {"commit.gpgsign", "false"}}) {
            runner.run(GitCommand.in(main).args("config", kv[0], kv[1]));
        }
        Files.writeString(main.resolve("a.txt"), "a\n");
        git.stage(List.of("a.txt"));
        git.commit("base", false);

        Path wt = tmp.resolve("linked");
        git.worktreeAdd(wt, "side", "HEAD");
        assertThat(wt.resolve(".git")).isRegularFile();   // the watcher must not rely on root/.git being a directory

        try (RepoSession session = RepoSession.open(wt, runner)) {
            CountDownLatch changed = new CountDownLatch(1);
            session.onChanged(changed::countDown);

            // Only the worktree's administrative HEAD changes: no working-tree file is touched,
            // so this can only be noticed by watching the real git directories.
            runner.run(GitCommand.in(wt).args("checkout", "-q", "-b", "other"));

            assertThat(changed.await(15, TimeUnit.SECONDS)).as("git directory change").isTrue();
        }
    }
}
