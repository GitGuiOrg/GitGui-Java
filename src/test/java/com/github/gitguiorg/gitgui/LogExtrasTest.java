package com.github.gitguiorg.gitgui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.gitguiorg.gitgui.git.GitClient;
import com.github.gitguiorg.gitgui.git.GitCommand;
import com.github.gitguiorg.gitgui.git.GitRunner;
import com.github.gitguiorg.gitgui.model.Commit;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LogExtrasTest {
    @TempDir Path tmp;
    GitRunner runner;
    Path dir;
    GitClient git;

    @BeforeEach
    void setUp() {
        runner = new GitRunner(null);
        dir = tmp.resolve("repo");
        git = GitClient.init(dir, runner);
        config("user.name", "Test");
        config("user.email", "t@example.com");
        config("commit.gpgsign", "false");
        config("core.autocrlf", "false");
        runner.run(GitCommand.in(dir).args("checkout", "-q", "-b", "main"));
    }

    void config(String k, String v) { runner.run(GitCommand.in(dir).args("config", "--local", k, v)); }

    void write(String rel, String content) throws IOException {
        Path p = dir.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    String commitAll(String msg) {
        git.stage(List.of("."));
        git.commit(msg, false);
        return runner.run(GitCommand.in(dir).args("rev-parse", "HEAD")).out().strip();
    }

    @Test
    void starsPersistInTheRepositoryAndFeedTheStarredLog() throws Exception {
        write("f.txt", "one\n");
        String first = commitAll("first");
        write("f.txt", "two\n");
        String second = commitAll("second");

        Path starFile = dir.resolve(".gcom/github/gitguiorg/gitgui/starred");
        assertThat(git.starredCommits()).isEmpty();
        assertThat(Files.exists(starFile)).isFalse();

        git.setStarred(first, true);
        git.setStarred(first, true);   // no duplicates
        assertThat(git.starredCommits()).containsExactly(first);
        assertThat(Files.exists(starFile)).isTrue();

        GitClient reopened = GitClient.open(dir, runner);
        assertThat(reopened.starredCommits()).containsExactly(first);

        List<Commit> onlyStarred = reopened.logByHash(reopened.starredCommits());
        assertThat(onlyStarred).extracting(Commit::hash).containsExactly(first);
        assertThat(onlyStarred.get(0).subject()).isEqualTo("first");

        reopened.setStarred(first, false);
        assertThat(reopened.starredCommits()).isEmpty();
        assertThat(Files.exists(starFile)).isFalse();
        assertThat(second).isNotEqualTo(first);
    }

    @Test
    void logByHashFetchesEveryHashWhenTheListNeedsSeveralInvocations() throws Exception {
        int n = 130;   // more than one git command line worth of hashes
        StringBuilder stream = new StringBuilder();
        for (int i = 1; i <= n; i++) {
            String msg = "c" + i;
            stream.append("commit refs/heads/main\n")
                    .append("mark :").append(i).append('\n')
                    .append("committer Test <t@example.com> ").append(1_700_000_000L + i).append(" +0000\n")
                    .append("data ").append(msg.length()).append('\n').append(msg).append('\n');
            if (i > 1) stream.append("from :").append(i - 1).append('\n');
            stream.append('\n');
        }
        runner.run(GitCommand.in(dir).args("fast-import", "--force", "--quiet").stdin(stream.toString()));

        List<String> hashes = git.log(0, n, false).stream().map(Commit::hash).toList();
        assertThat(hashes).hasSize(n);

        assertThat(git.logByHash(hashes)).extracting(Commit::hash).containsExactlyElementsOf(hashes);
        assertThat(git.logByHash(hashes)).extracting(Commit::subject).startsWith("c" + n);
    }

    @Test
    void squashFoldsTheRangeIntoOneCommitWithTheChosenMessage() throws Exception {
        write("f.txt", "one\n");
        String first = commitAll("one");
        write("f.txt", "two\n");
        commitAll("two");
        write("f.txt", "three\n");
        commitAll("three");

        Commit from = git.log(0, 10, false).stream().filter(c -> c.hash().equals(first)).findFirst().orElseThrow();
        git.squashFrom(from, "squashed message");

        List<Commit> log = git.log(0, 10, false);
        assertThat(log).hasSize(1);
        assertThat(log.get(0).subject()).isEqualTo("squashed message");
        assertThat(Files.readString(dir.resolve("f.txt"))).isEqualTo("three\n");
        assertThat(git.operation()).isEqualTo(com.github.gitguiorg.gitgui.model.Operation.NONE);
    }

    @Test
    void squashNeedsMoreThanOneCommit() throws Exception {
        write("f.txt", "one\n");
        String only = commitAll("only");
        Commit c = git.log(0, 1, false).get(0);
        assertThatThrownBy(() -> git.squashFrom(c, "message"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nothing to squash");
        assertThat(only).isEqualTo(c.hash());
    }

    @Test
    void mergeWithNoFfCreatesAMergeCommit() throws Exception {
        write("f.txt", "base\n");
        commitAll("base");
        git.createBranch("side", "HEAD", true);
        write("side.txt", "side\n");
        commitAll("side work");
        git.checkoutBranch("main");
        write("main.txt", "main\n");
        commitAll("main work");

        git.merge("side", true);
        Commit head = git.log(0, 1, false).get(0);
        assertThat(head.isMerge()).isTrue();
        assertThat(head.parents()).hasSize(2);
        assertThat(head.subject()).contains("side");
    }

    @Test
    void plainMergeFastForwards() throws Exception {
        write("f.txt", "base\n");
        commitAll("base");
        git.createBranch("side", "HEAD", true);
        write("side.txt", "side\n");
        String side = commitAll("side work");
        git.checkoutBranch("main");

        git.merge("side", false);
        assertThat(git.log(0, 1, false).get(0).hash()).isEqualTo(side);
        assertThat(git.log(0, 1, false).get(0).isMerge()).isFalse();
    }

    @Test
    void squashMergeStagesTheChangesWithoutCommitting() throws Exception {
        write("f.txt", "base\n");
        commitAll("base");
        git.createBranch("side", "HEAD", true);
        write("side.txt", "side\n");
        commitAll("side work");
        git.checkoutBranch("main");
        write("main.txt", "main\n");
        String mainHead = commitAll("main work");

        git.merge("side", false, true);

        assertThat(runner.run(GitCommand.in(dir).args("rev-parse", "HEAD")).out().strip()).isEqualTo(mainHead);
        assertThat(git.status(false).entries().stream().anyMatch(entry -> entry.path().equals("side.txt") && entry.isStaged()))
                .isTrue();
    }

    @Test
    void alreadyInHeadDetectsAncestorsAndCherryPicks() throws Exception {
        write("f.txt", "base\n");
        String base = commitAll("base");
        git.createBranch("side", "HEAD", true);
        write("side.txt", "side\n");
        String side = commitAll("side work");
        git.checkoutBranch("main");

        assertThat(git.isAlreadyInHead(base)).isTrue();
        assertThat(git.isAlreadyInHead(side)).isFalse();

        git.cherryPick(side);
        assertThat(git.isAlreadyInHead(side)).isTrue();
    }

    @Test
    void keepResetPreservesUnrelatedLocalChanges() throws Exception {
        write("a.txt", "a1\n");
        write("b.txt", "b1\n");
        String first = commitAll("first");
        write("a.txt", "a2\n");
        commitAll("second");
        write("b.txt", "local\n");

        git.reset(first, GitClient.ResetMode.KEEP);

        assertThat(runner.run(GitCommand.in(dir).args("rev-parse", "HEAD")).out().strip()).isEqualTo(first);
        assertThat(Files.readString(dir.resolve("a.txt"))).isEqualTo("a1\n");
        assertThat(Files.readString(dir.resolve("b.txt"))).isEqualTo("local\n");
    }
}
