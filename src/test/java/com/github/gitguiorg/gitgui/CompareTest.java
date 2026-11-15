package com.github.gitguiorg.gitgui;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.gitguiorg.gitgui.git.GitClient;
import com.github.gitguiorg.gitgui.git.GitCommand;
import com.github.gitguiorg.gitgui.git.GitRunner;
import com.github.gitguiorg.gitgui.model.Commit;
import com.github.gitguiorg.gitgui.model.DiffLine;
import com.github.gitguiorg.gitgui.model.FileDiff;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CompareTest {
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

    void config(String k, String v) { runner.run(GitCommand.in(dir).args("config", k, v)); }

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
    void comparesDivergingBranches() throws IOException {
        write("base.txt", "base\n");
        commitAll("base");

        git.createBranch("feature", "HEAD", true);
        write("a.txt", "one\n");
        commitAll("feature one");
        write("a.txt", "two\n");
        commitAll("feature two");

        git.checkoutBranch("main");
        write("b.txt", "main\n");
        commitAll("main one");

        List<FileDiff> diff = git.diffRange("main", "feature", null, 3, false, false);
        assertThat(diff).extracting(FileDiff::path).containsExactly("a.txt", "b.txt");
        assertThat(diff.get(0).hunks().get(0).lines()).extracting(DiffLine::text).contains("two");
        assertThat(diff.get(1).deletedFile()).isTrue();   // b.txt exists only on main
        assertThat(git.diffRange("main", "feature", "base.txt", 3, false, false)).isEmpty();

        // the merge base of the two branches is the same starting commit here
        assertThat(git.diffRange("main", "feature", null, 3, false, true))
                .extracting(FileDiff::path).containsExactly("a.txt");

        assertThat(git.logRange("main", "feature")).extracting(Commit::subject)
                .containsExactly("feature two", "feature one");
        assertThat(git.logRange("feature", "main")).extracting(Commit::subject)
                .containsExactly("main one");
    }

    @Test
    void comparesWithTheWorkingTreeAndWithHead() throws IOException {
        write("base.txt", "base\n");
        commitAll("base");
        write("base.txt", "committed\n");
        commitAll("change");
        write("base.txt", "working\n");

        List<FileDiff> worktree = git.diffRange("HEAD~1", null, null, 3, false, false);
        assertThat(worktree).extracting(FileDiff::path).containsExactly("base.txt");
        assertThat(worktree.get(0).hunks().get(0).lines()).extracting(DiffLine::text).contains("working");

        assertThat(git.diffRange("HEAD", null, null, 3, false, false))
                .extracting(FileDiff::path).containsExactly("base.txt");
        assertThat(git.logRange("HEAD~1", null)).extracting(Commit::subject).containsExactly("change");
        assertThat(git.logRange("HEAD", "HEAD")).isEmpty();
    }
}
