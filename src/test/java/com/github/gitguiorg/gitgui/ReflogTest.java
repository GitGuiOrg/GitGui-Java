package com.github.gitguiorg.gitgui;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.gitguiorg.gitgui.git.GitClient;
import com.github.gitguiorg.gitgui.git.GitCommand;
import com.github.gitguiorg.gitgui.git.GitRunner;
import com.github.gitguiorg.gitgui.git.ReflogParser;
import com.github.gitguiorg.gitgui.model.ReflogEntry;
import com.github.gitguiorg.gitgui.model.StashEntry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReflogTest {
    @TempDir Path tmp;
    GitRunner runner = new GitRunner(null);
    Path dir;
    GitClient git;

    @BeforeEach
    void setUp() throws Exception {
        dir = tmp.resolve("reflog repo");
        git = GitClient.init(dir, runner);
        for (String[] kv : new String[][]{{"user.name", "T"}, {"user.email", "t@x.it"}, {"commit.gpgsign", "false"},
                {"core.autocrlf", "false"}}) {
            runner.run(GitCommand.in(dir).args("config", kv[0], kv[1]));
        }
        runner.run(GitCommand.in(dir).args("checkout", "-q", "-b", "main"));
        commit("a.txt", "a", "A");
        commit("b.txt", "b", "B");
        commit("c.txt", "c", "C");
    }

    @Test
    void reflogFindsTheCommitLostToAHardReset() throws Exception {
        String head = git.status().headOid();
        String previous = git.log(0, 5, false).get(1).hash();
        git.reset(previous, GitClient.ResetMode.HARD);
        assertThat(git.log(0, 5, false).get(0).subject()).isEqualTo("B");

        List<ReflogEntry> reflog = git.reflog("HEAD", 10);
        assertThat(reflog).isNotEmpty();
        assertThat(reflog.get(0).selector()).isEqualTo("HEAD@{0}");
        assertThat(reflog.get(0).action()).isEqualTo("reset");
        assertThat(reflog.get(0).message()).contains("moving to");
        assertThat(reflog.get(0).hash()).isEqualTo(previous);
        assertThat(reflog.get(0).shortHash()).isEqualTo(previous.substring(0, 7));

        ReflogEntry lost = reflog.stream().filter(e -> e.hash().equals(head)).findFirst().orElseThrow();
        git.reset(lost.hash(), GitClient.ResetMode.HARD);
        assertThat(git.log(0, 5, false).get(0).subject()).isEqualTo("C");
    }

    @Test
    void reflogOfABranchAndLimit() {
        git.createBranch("feat", "HEAD~1", false);

        List<ReflogEntry> branch = git.reflog("feat", 10);
        assertThat(branch).isNotEmpty();
        assertThat(branch.get(0).action()).isEqualTo("branch");
        assertThat(branch.get(0).message()).contains("Created from");
        assertThat(branch.get(0).hash()).isEqualTo(git.log(0, 5, false).get(1).hash());

        assertThat(git.reflog("HEAD", 2)).hasSize(2);
    }

    @Test
    void stashStorePutsADroppedStashBack() throws Exception {
        Files.writeString(dir.resolve("a.txt"), "changed\n");
        git.stashPush("keep me", false);
        StashEntry entry = git.stashList().get(0);
        git.stashDrop(entry.ref());
        assertThat(git.stashList()).isEmpty();

        git.stashStore(entry.hash(), entry.message());
        assertThat(git.stashList()).hasSize(1);
        assertThat(git.stashList().get(0).message()).contains("keep me");
    }

    @Test
    void reflogOfASha256RepositoryUsesSixtyFourCharacterIds() throws Exception {
        Path sha = tmp.resolve("sha256 repo");
        Files.createDirectories(sha);
        runner.run(GitCommand.in(sha).args("init", "-q", "--object-format=sha256"));
        for (String[] kv : new String[][]{{"user.name", "T"}, {"user.email", "t@x.it"}, {"commit.gpgsign", "false"}}) {
            runner.run(GitCommand.in(sha).args("config", kv[0], kv[1]));
        }
        GitClient g = GitClient.open(sha, runner);
        Files.writeString(sha.resolve("a.txt"), "a\n");
        g.stage(List.of("a.txt"));
        g.commit("A", false);

        ReflogEntry e = g.reflog("HEAD", 5).get(0);
        assertThat(e.hash()).hasSize(64);
        assertThat(com.github.gitguiorg.gitgui.util.Hashes.isObjectId(g.status().headOid())).isTrue();
    }

    @Test
    void parserSplitsActionAndMessage() {
        String out = "\u001e" + "a".repeat(40) + "\u0000aaaaaaaa\u0000HEAD@{2}\u0000"
                + "checkout: moving from main to feat\u00002026-11-05T10:00:00+01:00\n";
        ReflogEntry e = ReflogParser.parse(out).get(0);
        assertThat(e.hash()).isEqualTo("a".repeat(40));
        assertThat(e.selector()).isEqualTo("HEAD@{2}");
        assertThat(e.action()).isEqualTo("checkout");
        assertThat(e.message()).isEqualTo("moving from main to feat");
        assertThat(e.summary()).isEqualTo("checkout: moving from main to feat");
        assertThat(e.date()).isEqualTo(Instant.parse("2026-11-05T09:00:00Z"));
    }

    private void commit(String name, String content, String message) throws Exception {
        Files.writeString(dir.resolve(name), content);
        git.stage(List.of(name));
        git.commit(message, false);
    }
}
