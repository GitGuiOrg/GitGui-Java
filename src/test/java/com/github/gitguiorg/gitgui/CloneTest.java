package com.github.gitguiorg.gitgui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.gitguiorg.gitgui.git.CancelToken;
import com.github.gitguiorg.gitgui.git.GitCancelledException;
import com.github.gitguiorg.gitgui.git.GitClient;
import com.github.gitguiorg.gitgui.git.GitCommand;
import com.github.gitguiorg.gitgui.git.GitException;
import com.github.gitguiorg.gitgui.git.GitRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CloneTest {
    @TempDir Path tmp;
    GitRunner runner = new GitRunner(null);

    @Test
    void repoNameFromUrl() {
        assertThat(GitClient.repoNameFromUrl("https://github.com/a/b.git")).isEqualTo("b");
        assertThat(GitClient.repoNameFromUrl("https://github.com/a/b/")).isEqualTo("b");
        assertThat(GitClient.repoNameFromUrl("git@github.com:a/b.git")).isEqualTo("b");
        assertThat(GitClient.repoNameFromUrl("C:\\repos\\my-repo\\")).isEqualTo("my-repo");
        assertThat(GitClient.repoNameFromUrl("file:///tmp/r.git")).isEqualTo("r");
        assertThat(GitClient.repoNameFromUrl("  ")).isEmpty();
    }

    private Path makeSource() throws Exception {
        Path src = tmp.resolve("src");
        GitClient g = GitClient.init(src, runner);
        for (String[] kv : new String[][]{{"user.name", "T"}, {"user.email", "t@x.it"}, {"commit.gpgsign", "false"}}) {
            runner.run(GitCommand.in(src).args("config", kv[0], kv[1]));
        }
        Files.writeString(src.resolve("a.txt"), "a");
        g.stage(List.of("a.txt"));
        g.commit("first", false);
        Path bare = tmp.resolve("remote.git");
        runner.run(GitCommand.in(tmp).args("clone", "-q", "--bare", src.toString(), bare.getFileName().toString()));
        return bare;
    }

    @Test
    void clonesAndOpens() throws Exception {
        Path bare = makeSource();
        List<String> phases = new ArrayList<>();
        Path target = GitClient.cloneRepository(runner, bare.toUri().toString(), tmp.resolve("work dir"), "my clone",
                p -> phases.add(p.phase()), null);
        assertThat(target).isEqualTo(tmp.resolve("work dir").resolve("my clone"));
        assertThat(target.resolve("a.txt")).exists();
        GitClient c = GitClient.open(target, runner);
        assertThat(c.log(0, 5, false)).extracting(x -> x.subject()).containsExactly("first");
        assertThat(c.remotes()).containsExactly("origin");
    }

    @Test
    void failureLeavesNoDirectory() {
        Path parent = tmp.resolve("p");
        assertThatThrownBy(() -> GitClient.cloneRepository(runner, tmp.resolve("does-not-exist.git").toUri().toString(),
                parent, "x", null, null)).isInstanceOf(GitException.class);
        assertThat(parent.resolve("x")).doesNotExist();
    }

    @Test
    void cancelledCloneIsCleanedUp() throws Exception {
        Path bare = makeSource();
        CancelToken t = new CancelToken();
        t.cancel();
        Path parent = tmp.resolve("p2");
        assertThatThrownBy(() -> GitClient.cloneRepository(runner, bare.toUri().toString(), parent, "x", null, t))
                .isInstanceOf(GitCancelledException.class);
        assertThat(parent.resolve("x")).doesNotExist();
    }
}
