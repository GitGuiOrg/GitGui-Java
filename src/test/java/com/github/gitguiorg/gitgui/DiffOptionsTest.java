package com.github.gitguiorg.gitgui;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.gitguiorg.gitgui.git.GitClient;
import com.github.gitguiorg.gitgui.git.GitClient.DiffOptions;
import com.github.gitguiorg.gitgui.git.GitCommand;
import com.github.gitguiorg.gitgui.git.GitRunner;
import com.github.gitguiorg.gitgui.model.DiffLine;
import com.github.gitguiorg.gitgui.model.FileDiff;
import com.github.gitguiorg.gitgui.service.DiffPreferences;
import com.github.gitguiorg.gitgui.service.SettingsService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiffOptionsTest {
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

    void write(String content) throws IOException { Files.writeString(dir.resolve("f.txt"), content); }

    void commitAll(String msg) {
        git.stage(List.of("."));
        git.commit(msg, false);
    }

    @Test
    void preferencesAreSharedAndPersisted() {
        Path config = tmp.resolve("config");
        SettingsService service = new SettingsService(config);
        DiffPreferences prefs = new DiffPreferences(service);

        assertThat(prefs.sideBySideProperty().get()).isFalse();
        assertThat(prefs.syntaxProperty().get()).isTrue();

        prefs.sideBySideProperty().set(true);
        prefs.syntaxProperty().set(false);
        prefs.ignoreCrAtEolProperty().set(true);
        prefs.ignoreBlankLinesProperty().set(true);
        prefs.contextLinesProperty().set(5);
        assertThat(prefs.ignoresDifferences()).isTrue();

        DiffPreferences reloaded = new DiffPreferences(new SettingsService(config));
        assertThat(reloaded.sideBySideProperty().get()).isTrue();
        assertThat(reloaded.syntaxProperty().get()).isFalse();
        assertThat(reloaded.ignoreCrAtEolProperty().get()).isTrue();
        assertThat(reloaded.ignoreBlankLinesProperty().get()).isTrue();
        assertThat(reloaded.contextLinesProperty().get()).isEqualTo(5);
    }

    @Test
    void outOfRangeLimitsAreClamped() {
        SettingsService service = new SettingsService(tmp.resolve("config-limits"));
        service.get().diffContextLines = -3;
        service.get().diffMaxLines = 1;
        DiffPreferences prefs = new DiffPreferences(service);
        assertThat(prefs.contextLinesProperty().get()).isZero();
        assertThat(prefs.maxLinesProperty().get()).isEqualTo(100);

        prefs.contextLinesProperty().set(-8);
        assertThat(prefs.contextLinesProperty().get()).isZero();
        prefs.maxLinesProperty().set(5);
        assertThat(prefs.maxLinesProperty().get()).isEqualTo(100);
    }

    @Test
    void ignoreCrAtEolHidesLineEndingChanges() throws Exception {
        write("a\r\nb\r\n");
        commitAll("crlf");
        write("a\nb\n");

        assertThat(git.diffWorkingTree("f.txt", 3)).isNotEmpty();
        assertThat(git.diffWorkingTree("f.txt", new DiffOptions(3, false, true, false))).isEmpty();
    }

    @Test
    void ignoreBlankLinesHidesInsertedEmptyLines() throws Exception {
        write("a\nb\n");
        commitAll("base");
        write("a\n\n\nb\n");

        assertThat(git.diffWorkingTree("f.txt", 3)).isNotEmpty();
        assertThat(git.diffWorkingTree("f.txt", new DiffOptions(3, false, false, true))).isEmpty();
    }

    @Test
    void contextSizeChangesTheHunkAroundTheChange() throws Exception {
        StringBuilder lines = new StringBuilder();
        for (int i = 1; i <= 12; i++) lines.append("line ").append(i).append('\n');
        write(lines.toString());
        commitAll("base");
        write(lines.toString().replace("line 6\n", "line 6 changed\n"));

        List<FileDiff> tight = git.diffWorkingTree("f.txt", new DiffOptions(0, false, false, false));
        List<FileDiff> wide = git.diffWorkingTree("f.txt", new DiffOptions(3, false, false, false));

        assertThat(texts(tight)).noneMatch(t -> t.equals("line 3"));
        assertThat(texts(wide)).contains("line 3", "line 9");
    }

    private static List<String> texts(List<FileDiff> files) {
        return files.stream().flatMap(f -> f.hunks().stream())
                .flatMap(h -> h.lines().stream()).map(DiffLine::text).toList();
    }
}
