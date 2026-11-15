package com.github.gitguiorg.gitgui;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.gitguiorg.gitgui.git.GitClient;
import com.github.gitguiorg.gitgui.git.GitClient.BisectState;
import com.github.gitguiorg.gitgui.git.GitCommand;
import com.github.gitguiorg.gitgui.git.GitRunner;
import com.github.gitguiorg.gitgui.model.MergeOutcome;
import com.github.gitguiorg.gitgui.model.Operation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BisectTest {
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

    String commit(String content) throws IOException {
        Files.writeString(dir.resolve("f.txt"), content);
        git.stage(List.of("f.txt"));
        git.commit(content.strip(), false);
        return head();
    }

    String head() {
        return runner.run(GitCommand.in(dir).args("rev-parse", "HEAD")).out().strip();
    }

    boolean isAncestor(String a, String b) {
        return runner.runRaw(GitCommand.in(dir).args("merge-base", "--is-ancestor", a, b)).exitCode() == 0;
    }

    @Test
    void noBisectMeansAnEmptyState() throws Exception {
        commit("base");
        assertThat(git.operation()).isEqualTo(Operation.NONE);
        assertThat(git.bisectState().active()).isFalse();
        assertThat(git.bisectState().candidates()).isZero();
    }

    @Test
    void guidedBisectFindsTheFirstBadCommit() throws Exception {
        String good = commit("c1");
        commit("c2");
        commit("c3");
        String firstBad = commit("c4");
        commit("c5");
        commit("c6");
        String tip = commit("c7");

        git.bisectMark(good, true);
        assertThat(git.operation()).isEqualTo(Operation.BISECT);
        BisectState started = git.bisectState();
        assertThat(started.active()).isTrue();
        assertThat(started.good()).containsExactly(good);
        assertThat(started.bad()).isNull();

        git.bisectMark(tip, false);
        BisectState marked = git.bisectState();
        assertThat(marked.bad()).isEqualTo(tip);
        assertThat(marked.candidates()).isEqualTo(6);
        assertThat(marked.steps()).isEqualTo(3);
        assertThat(head()).isNotEqualTo(tip);   // git checked out a candidate

        int guard = 0;
        while (!head().equals(firstBad) && guard++ < 10) {
            String candidate = head();
            git.bisectMark(candidate, isAncestor(candidate, firstBad));
        }
        assertThat(head()).isEqualTo(firstBad);

        git.bisectReset();
        assertThat(git.operation()).isEqualTo(Operation.NONE);
        assertThat(head()).isEqualTo(tip);
        assertThat(runner.run(GitCommand.in(dir).args("rev-parse", "--abbrev-ref", "HEAD")).out().strip())
                .isEqualTo("main");
    }

    @Test
    void skipMovesToAnotherCandidate() throws Exception {
        String good = commit("c1");
        commit("c2");
        commit("c3");
        String tip = commit("c4");
        git.bisectMark(good, true);
        git.bisectMark(tip, false);

        String before = head();
        git.bisectSkip();
        assertThat(git.operation()).isEqualTo(Operation.BISECT);
        assertThat(git.bisectState().active()).isTrue();
        assertThat(head()).isNotEqualTo(before);

        git.bisectReset();
    }

    @Test
    void continueMarksTheCheckedOutCandidateAsGood() throws Exception {
        String good = commit("c1");
        commit("c2");
        commit("c3");
        commit("c4");
        String tip = commit("c5");
        git.bisectMark(good, true);
        git.bisectMark(tip, false);

        String candidate = head();
        assertThat(candidate).isNotEqualTo(tip);
        assertThat(git.operationContinue(Operation.BISECT)).isEqualTo(MergeOutcome.DONE);
        assertThat(git.bisectState().good()).contains(candidate);
        assertThat(head()).isNotEqualTo(candidate);

        git.bisectReset();
    }
}
