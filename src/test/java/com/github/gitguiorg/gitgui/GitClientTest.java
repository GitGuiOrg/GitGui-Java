package com.github.gitguiorg.gitgui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.gitguiorg.gitgui.git.*;
import com.github.gitguiorg.gitgui.model.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitClientTest {
    @TempDir Path tmp;
    GitRunner runner;
    Path dir;
    GitClient git;

    @BeforeEach
    void setUp() {
        runner = new GitRunner(null);
        dir = tmp.resolve("repo");
        git = GitClient.init(dir, runner);
        config(dir, "user.name", "Test");
        config(dir, "user.email", "t@example.com");
        config(dir, "commit.gpgsign", "false");
        config(dir, "core.autocrlf", "false");
        run(dir, "checkout", "-q", "-b", "main");
    }

    void config(Path d, String k, String v) { run(d, "config", k, v); }

    void run(Path d, String... args) { runner.run(GitCommand.in(d).args(args)); }

    void write(String rel, String content) throws IOException {
        Path p = dir.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    void commitAll(String msg) {
        git.stage(List.of("."));
        git.commit(msg, false);
    }

    @Test
    void emptyRepo() {
        assertThat(git.hasCommits()).isFalse();
        assertThat(git.log(0, 10, true)).isEmpty();
    }

    @Test
    void commitStatusAndLogPaging() throws IOException {
        write("a.txt", "1\n");
        assertThat(git.status().entries()).extracting(StatusEntry::path).containsExactly("a.txt");
        for (int i = 0; i < 5; i++) {
            write("a.txt", "v" + i + "\n");
            commitAll("commit " + i + "\n\nbody " + i);
        }
        assertThat(git.status().entries()).isEmpty();
        assertThat(git.status().branch()).isEqualTo("main");

        List<Commit> page1 = git.log(0, 2, false);
        List<Commit> page2 = git.log(2, 2, false);
        assertThat(page1).extracting(Commit::subject).containsExactly("commit 4", "commit 3");
        assertThat(page2).extracting(Commit::subject).containsExactly("commit 2", "commit 1");
        assertThat(page1.get(0).body()).isEqualTo("body 4");
        assertThat(page1.get(0).refs()).contains("HEAD -> main");
        assertThat(git.fileHistory("a.txt", 0, 100)).hasSize(5);
    }

    @Test
    void stageUnstageAndUnicodePaths() throws IOException {
        write("base.txt", "x\n");
        commitAll("init");
        write("città/è file.txt", "hello\n");
        write("base.txt", "y\n");
        git.stage(List.of("città/è file.txt"));
        RepoStatus s = git.status();
        assertThat(s.entries()).anySatisfy(e -> {
            assertThat(e.path()).isEqualTo("città/è file.txt");
            assertThat(e.isStaged()).isTrue();
        });
        git.unstage(List.of("città/è file.txt"));
        assertThat(git.status().entries()).anySatisfy(e ->
                assertThat(e.kind()).isEqualTo(StatusEntry.Kind.UNTRACKED));
    }

    @Test
    void unstageInRepoWithoutCommits() throws IOException {
        write("a.txt", "1\n");
        git.stage(List.of("a.txt"));
        git.unstage(List.of("a.txt"));
        assertThat(git.status().entries().get(0).kind()).isEqualTo(StatusEntry.Kind.UNTRACKED);
    }

    @Test
    void amend() throws IOException {
        write("a.txt", "1\n");
        commitAll("first");
        git.commit("renamed", true);
        assertThat(git.log(0, 10, false)).extracting(Commit::subject).containsExactly("renamed");
    }

    @Test
    void hunkAndLineStaging() throws IOException {
        StringBuilder base = new StringBuilder();
        for (int i = 1; i <= 30; i++) base.append("line").append(i).append('\n');
        write("f.txt", base.toString());
        commitAll("init");
        write("f.txt", base.toString().replace("line2\n", "LINE2\n").replace("line28\n", "LINE28\n")
                .replace("line29\n", "line29\nextra\n"));

        FileDiff d = git.diffWorkingTree("f.txt", 3).get(0);
        assertThat(d.hunks()).hasSize(2);

        git.stageHunk(d, 0, null);
        List<FileDiff> staged = git.diffStaged("f.txt", 3);
        assertThat(staged.get(0).hunks()).hasSize(1);
        assertThat(staged.get(0).hunks().get(0).lines()).extracting(DiffLine::text).contains("LINE2");
        assertThat(git.diffWorkingTree("f.txt", 3).get(0).hunks()).hasSize(1);

        // line level: stage only the added "extra" line of the remaining hunk
        FileDiff rest = git.diffWorkingTree("f.txt", 3).get(0);
        List<DiffLine> lines = rest.hunks().get(0).lines();
        int extra = -1;
        for (int i = 0; i < lines.size(); i++) if (lines.get(i).text().equals("extra")) extra = i;
        git.stageHunk(rest, 0, java.util.Set.of(extra));
        FileDiff nowStaged = git.diffStaged("f.txt", 0).get(0);
        assertThat(nowStaged.hunks()).extracting(h -> h.lines().stream().map(DiffLine::text).toList())
                .anySatisfy(l -> assertThat(l).contains("extra"))
                .noneSatisfy(l -> assertThat(l).contains("LINE28"));

        // unstage the "extra" hunk again
        FileDiff st = git.diffStaged("f.txt", 3).get(0);
        int idx = -1;
        for (int i = 0; i < st.hunks().size(); i++)
            if (st.hunks().get(i).lines().stream().anyMatch(l -> l.text().equals("extra"))) idx = i;
        git.unstageHunk(st, idx, null);
        assertThat(git.diffStaged("f.txt", 3).get(0).hunks()).hasSize(1);
    }

    @Test
    void discardHunk() throws IOException {
        write("f.txt", "a\nb\nc\n");
        commitAll("init");
        write("f.txt", "a\nB\nc\n");
        FileDiff d = git.diffWorkingTree("f.txt", 3).get(0);
        git.discardHunk(d, 0, null);
        assertThat(Files.readString(dir.resolve("f.txt"))).isEqualTo("a\nb\nc\n");
    }

    @Test
    void untrackedAndCommitDiff() throws IOException {
        write("a.txt", "1\n");
        commitAll("init");
        write("new.txt", "hello\n");
        FileDiff u = git.diffUntracked("new.txt", 3).get(0);
        assertThat(u.newFile()).isTrue();
        assertThat(u.hunks().get(0).lines()).extracting(DiffLine::text).containsExactly("hello");
        FileDiff c = git.diffCommit(git.log(0, 1, false).get(0).hash(), 3).get(0);
        assertThat(c.path()).isEqualTo("a.txt");
    }

    @Test
    void branchesTagsAndMerge() throws IOException {
        write("a.txt", "1\n");
        commitAll("init");
        git.createBranch("feature", "HEAD", true);
        write("b.txt", "b\n");
        commitAll("feat");
        git.checkoutBranch("main");
        git.createTag("v1", "HEAD", null);
        git.createTag("v2", "HEAD", "annotated");

        assertThat(git.merge("feature")).isEqualTo(MergeOutcome.DONE);
        List<GitRef> refs = git.refs();
        assertThat(refs).extracting(GitRef::shortName).contains("main", "feature", "v1", "v2");
        assertThat(refs).filteredOn(GitRef::head).extracting(GitRef::shortName).containsExactly("main");
        String head = git.log(0, 1, false).get(0).hash();
        assertThat(refs).filteredOn(r -> r.shortName().equals("v2")).extracting(GitRef::hash)
                .allSatisfy(h -> assertThat(h).isNotEqualTo(head));

        git.deleteTag("v1");
        git.deleteBranch("feature", false);
        assertThat(git.refs()).extracting(GitRef::shortName).doesNotContain("v1", "feature");
    }

    @Test
    void mergeConflictAndAbort() throws IOException {
        write("a.txt", "base\n");
        commitAll("init");
        git.createBranch("other", "HEAD", true);
        write("a.txt", "other\n");
        commitAll("other");
        git.checkoutBranch("main");
        write("a.txt", "main\n");
        commitAll("main");

        assertThat(git.merge("other")).isEqualTo(MergeOutcome.CONFLICTS);
        assertThat(git.status().hasConflicts()).isTrue();
        git.mergeAbort();
        assertThat(git.status().hasConflicts()).isFalse();
    }

    @Test
    void errorsCarryStderr() {
        assertThatThrownBy(() -> git.checkoutBranch("does-not-exist"))
                .isInstanceOf(GitException.class)
                .satisfies(e -> assertThat(((GitException) e).exitCode()).isNotZero());
    }

    @Test
    void cancelKillsProcess() {
        CancelToken t = new CancelToken();
        t.cancel();
        assertThatThrownBy(() -> runner.run(GitCommand.in(dir).args("status").cancel(t)))
                .isInstanceOf(GitCancelledException.class);
    }

    @Test
    void pushFetchPullWithProgress() throws IOException {
        Path bare = tmp.resolve("remote.git");
        Files.createDirectories(bare);
        run(bare, "init", "--bare", "-q", "-b", "main");
        run(dir, "remote", "add", "origin", bare.toUri().toString());
        write("a.txt", "1\n");
        commitAll("init");

        List<GitProgress> progress = new ArrayList<>();
        git.push("origin", "main", true, false, progress::add, null);
        assertThat(git.status().upstream()).isEqualTo("origin/main");
        assertThat(git.remotes()).containsExactly("origin");

        // second clone pushes a commit, first one pulls it
        Path other = tmp.resolve("other");
        run(tmp, "clone", "-q", bare.toUri().toString(), "other");
        config(other, "user.name", "O");
        config(other, "user.email", "o@example.com");
        config(other, "commit.gpgsign", "false");
        Files.writeString(other.resolve("o.txt"), "o\n");
        run(other, "add", ".");
        run(other, "commit", "-q", "-m", "from other");
        run(other, "push", "-q");

        git.fetch(null, true, progress::add, null);
        assertThat(git.status().behind()).isEqualTo(1);
        git.pull(GitClient.PullMode.FF_ONLY, progress::add, null);
        assertThat(git.status().behind()).isZero();
        assertThat(git.log(0, 1, false).get(0).subject()).isEqualTo("from other");
    }
}