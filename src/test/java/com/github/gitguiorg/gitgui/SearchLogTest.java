package com.github.gitguiorg.gitgui;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.gitguiorg.gitgui.git.GitClient;
import com.github.gitguiorg.gitgui.git.GitCommand;
import com.github.gitguiorg.gitgui.git.GitRunner;
import com.github.gitguiorg.gitgui.model.Commit;
import com.github.gitguiorg.gitgui.model.LogFilter;
import com.github.gitguiorg.gitgui.model.LogFilter.Mode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SearchLogTest {
    @TempDir Path tmp;
    GitRunner runner = new GitRunner(null);
    Path dir;
    GitClient git;

    void config(String k, String v) { runner.run(GitCommand.in(dir).args("config", k, v)); }

    void commit(String author, String file, String message) throws Exception {
        config("user.name", author);
        Files.writeString(dir.resolve(file), message);
        git.stage(List.of(file));
        git.commit(message, false);
    }

    @BeforeEach
    void setUp() throws Exception {
        dir = tmp.resolve("repo");
        git = GitClient.init(dir, runner);
        config("user.email", "t@x.it");
        config("commit.gpgsign", "false");
        runner.run(GitCommand.in(dir).args("checkout", "-q", "-b", "main"));
        commit("Alice", "a.txt", "Fix login bug");
        commit("Bob", "b.txt", "Add feature [x]");
        git.createBranch("side", "HEAD", true);
        commit("Alice", "s.txt", "Side only work");
        git.checkoutBranch("main");
    }

    List<String> subjects(LogFilter f) {
        return git.searchLog(f, 0, 50).stream().map(Commit::subject).toList();
    }

    @Test
    void messageIsCaseInsensitiveAndLiteral() {
        assertThat(subjects(new LogFilter(Mode.MESSAGE, "LOGIN", null))).containsExactly("Fix login bug");
        assertThat(subjects(new LogFilter(Mode.MESSAGE, "[x]", null))).containsExactly("Add feature [x]");
    }

    @Test
    void authorAndFile() {
        assertThat(subjects(new LogFilter(Mode.AUTHOR, "alice", null))).containsExactly("Side only work", "Fix login bug");
        assertThat(subjects(new LogFilter(Mode.FILE, "b.txt", null))).containsExactly("Add feature [x]");
    }

    @Test
    void contentFindsTheCommitThatChangedIt() {
        assertThat(subjects(new LogFilter(Mode.CONTENT, "login", null))).containsExactly("Fix login bug");
        assertThat(subjects(new LogFilter(Mode.CONTENT, "LOGIN", null))).containsExactly("Fix login bug");
        assertThat(subjects(new LogFilter(Mode.CONTENT, "text that never existed", null))).isEmpty();
        assertThat(subjects(new LogFilter(Mode.CONTENT, "side only", "main"))).isEmpty();
    }

    @Test
    void hashPrefixFindsOneCommit() {
        Commit c = git.log(0, 10, false).get(0);
        assertThat(subjects(new LogFilter(Mode.HASH, c.hash().substring(0, 8), null))).containsExactly(c.subject());
        assertThat(subjects(new LogFilter(Mode.HASH, "zzzzzzzz", null))).isEmpty();
    }

    @Test
    void hashSearchRespectsTheBranchFilter() {
        String side = git.log(0, 50, true).stream()
                .filter(c -> c.subject().equals("Side only work")).findFirst().orElseThrow().hash();
        assertThat(subjects(new LogFilter(Mode.HASH, side, null))).containsExactly("Side only work");
        assertThat(subjects(new LogFilter(Mode.HASH, side, "main"))).isEmpty();
        assertThat(subjects(new LogFilter(Mode.HASH, side, "side"))).containsExactly("Side only work");
    }

    @Test
    void branchFilterRestrictsHistory() {
        assertThat(subjects(new LogFilter(Mode.MESSAGE, "", null))).contains("Side only work");
        assertThat(subjects(new LogFilter(Mode.MESSAGE, "", "main"))).containsExactly("Add feature [x]", "Fix login bug");
        assertThat(subjects(new LogFilter(Mode.MESSAGE, "", "HEAD"))).doesNotContain("Side only work");
        assertThat(subjects(new LogFilter(Mode.MESSAGE, "work", "main"))).isEmpty();
    }

    @Test
    void paging() {
        assertThat(git.searchLog(new LogFilter(Mode.AUTHOR, "alice", null), 1, 5)).extracting(Commit::subject)
                .containsExactly("Fix login bug");
    }

    @Test
    void filterFlags() {
        assertThat(LogFilter.NONE.isActive()).isFalse();
        assertThat(new LogFilter(Mode.MESSAGE, "x", null).flat()).isTrue();
        assertThat(new LogFilter(Mode.MESSAGE, "", "main").flat()).isFalse();
        assertThat(new LogFilter(Mode.MESSAGE, "", "main").isActive()).isTrue();
    }
}
