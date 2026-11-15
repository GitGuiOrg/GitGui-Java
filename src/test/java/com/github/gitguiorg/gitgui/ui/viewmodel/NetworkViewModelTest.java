package com.github.gitguiorg.gitgui.ui.viewmodel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.gitguiorg.gitgui.git.GitClient;
import com.github.gitguiorg.gitgui.git.GitCommand;
import com.github.gitguiorg.gitgui.git.GitException;
import com.github.gitguiorg.gitgui.git.GitRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NetworkViewModelTest {
    @TempDir Path tmp;

    private void git(GitRunner r, Path d, String... a) { r.run(GitCommand.in(d).args(a)); }

    @Test
    void realRejectedPushIsRecognised() throws Exception {
        GitRunner r = new GitRunner(null);
        Path bare = Files.createDirectories(tmp.resolve("remote.git"));
        git(r, bare, "init", "--bare", "-q", "-b", "main");
        Path a = tmp.resolve("a");
        Path b = tmp.resolve("b");
        git(r, tmp, "clone", "-q", bare.toUri().toString(), "a");
        for (Path p : new Path[]{a}) {
            git(r, p, "config", "user.name", "T"); git(r, p, "config", "user.email", "t@x.it");
            git(r, p, "config", "commit.gpgsign", "false"); git(r, p, "checkout", "-q", "-b", "main");
        }
        Files.writeString(a.resolve("f.txt"), "1\n");
        git(r, a, "add", "."); git(r, a, "commit", "-q", "-m", "one"); git(r, a, "push", "-q", "-u", "origin", "main");

        git(r, tmp, "clone", "-q", bare.toUri().toString(), "b");
        git(r, b, "config", "user.name", "T"); git(r, b, "config", "user.email", "t@x.it");
        git(r, b, "config", "commit.gpgsign", "false");
        Files.writeString(b.resolve("g.txt"), "2\n");
        git(r, b, "add", "."); git(r, b, "commit", "-q", "-m", "two"); git(r, b, "push", "-q");

        Files.writeString(a.resolve("h.txt"), "3\n");
        git(r, a, "add", "."); git(r, a, "commit", "-q", "-m", "three");
        GitClient client = GitClient.open(a, r);
        assertThatThrownBy(() -> client.push(null, null, false, false, null, null))
                .isInstanceOfSatisfying(GitException.class,
                        e -> assertThat(NetworkViewModel.isRejectedPush(e.stderr())).isTrue());
        assertThat(NetworkViewModel.isRejectedPush("fatal: could not read Username")).isFalse();
    }
}