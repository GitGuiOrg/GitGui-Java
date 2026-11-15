package com.github.gitguiorg.gitgui;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.gitguiorg.gitgui.git.GitClient;
import com.github.gitguiorg.gitgui.git.GitCommand;
import com.github.gitguiorg.gitgui.git.GitRunner;
import com.github.gitguiorg.gitgui.model.StatusEntry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The reset mode reaches git as a flag, so lower-casing it must not follow the user's locale. */
class ResetLocaleTest {
    @TempDir Path tmp;
    GitRunner runner = new GitRunner(null);
    Path dir;
    GitClient git;
    Locale previous;

    @BeforeEach
    void setUp() throws Exception {
        dir = tmp.resolve("repo");
        git = GitClient.init(dir, runner);
        for (String[] kv : new String[][]{{"user.name", "T"}, {"user.email", "t@x.it"}, {"commit.gpgsign", "false"}}) {
            runner.run(GitCommand.in(dir).args("config", kv[0], kv[1]));
        }
        runner.run(GitCommand.in(dir).args("checkout", "-q", "-b", "main"));
        Files.writeString(dir.resolve("a.txt"), "one\n");
        git.stage(List.of("a.txt"));
        git.commit("A", false);

        previous = Locale.getDefault();
        // Turkish lower-cases "MIXED" to "mıxed": git reset would fail with an unknown option.
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
    }

    @AfterEach
    void restoreLocale() {
        if (previous != null) Locale.setDefault(previous);
    }

    @Test
    void mixedResetKeepsTheStagedChangeInTheWorkingTree() throws Exception {
        Files.writeString(dir.resolve("a.txt"), "two\n");
        git.stage(List.of("a.txt"));
        assertThat(git.status().entries()).singleElement().satisfies(e -> assertThat(e.isStaged()).isTrue());

        git.reset("HEAD", GitClient.ResetMode.MIXED);

        List<StatusEntry> entries = git.status().entries();
        assertThat(entries).singleElement().satisfies(e -> {
            assertThat(e.isStaged()).isFalse();
            assertThat(e.worktreeState()).isEqualTo('M');
        });
        assertThat(Files.readString(dir.resolve("a.txt"))).isEqualTo("two\n");
    }
}
