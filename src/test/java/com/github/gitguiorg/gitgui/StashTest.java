package com.github.gitguiorg.gitgui;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.gitguiorg.gitgui.git.GitClient;
import com.github.gitguiorg.gitgui.git.GitCommand;
import com.github.gitguiorg.gitgui.git.GitRunner;
import com.github.gitguiorg.gitgui.model.MergeOutcome;
import com.github.gitguiorg.gitgui.model.StashEntry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StashTest {
    @TempDir Path tmp;

    @Test
    void stashLifecycle() throws Exception {
        GitRunner r = new GitRunner(null);
        Path dir = tmp.resolve("repo");
        GitClient git = GitClient.init(dir, r);
        for (String[] kv : new String[][]{{"user.name", "T"}, {"user.email", "t@x.it"}, {"commit.gpgsign", "false"}}) {
            r.run(GitCommand.in(dir).args("config", kv[0], kv[1]));
        }
        Files.writeString(dir.resolve("a.txt"), "1\n");
        git.stage(List.of("a.txt"));
        git.commit("init", false);

        Files.writeString(dir.resolve("a.txt"), "2\n");
        Files.writeString(dir.resolve("new.txt"), "n\n");
        git.stashPush("my wip", true);
        assertThat(git.status().entries()).isEmpty();

        List<StashEntry> list = git.stashList();
        assertThat(list).hasSize(1);
        assertThat(list.get(0).ref()).isEqualTo("stash@{0}");
        assertThat(list.get(0).message()).contains("my wip");
        assertThat(git.stashDiff("stash@{0}", 3)).extracting(d -> d.path()).contains("a.txt", "new.txt");

        assertThat(git.stashApply("stash@{0}")).isEqualTo(MergeOutcome.DONE);
        assertThat(git.status().entries()).hasSize(2);
        assertThat(git.stashList()).hasSize(1);

        git.discard(List.of("a.txt"));
        Files.delete(dir.resolve("new.txt"));
        assertThat(git.stashPop("stash@{0}")).isEqualTo(MergeOutcome.DONE);
        assertThat(git.stashList()).isEmpty();

        git.stashPush(null, false);
        git.stashDrop("stash@{0}");
        assertThat(git.stashList()).isEmpty();
    }
}