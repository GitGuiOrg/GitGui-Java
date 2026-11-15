package com.github.gitguiorg.gitgui;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.gitguiorg.gitgui.git.GitClient;
import com.github.gitguiorg.gitgui.git.GitCommand;
import com.github.gitguiorg.gitgui.git.GitRunner;
import com.github.gitguiorg.gitgui.model.StatusEntry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IgnoreCleanTest {
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
    void ignoreRuleInGitignoreHidesTheFile() throws IOException {
        write(".gitignore", "");
        commitAll("init");
        write("build.log", "noise\n");
        assertThat(git.status().entries()).extracting(StatusEntry::path).containsExactly("build.log");

        git.ignorePattern("*.log", false);

        assertThat(Files.readString(dir.resolve(".gitignore"))).isEqualTo("*.log\n");
        assertThat(git.status().entries()).filteredOn(e -> !e.path().equals(".gitignore")).isEmpty();
        assertThat(git.status(true).entries()).anySatisfy(e -> {
            assertThat(e.path()).isEqualTo("build.log");
            assertThat(e.kind()).isEqualTo(StatusEntry.Kind.IGNORED);
        });
    }

    @Test
    void ignoreRuleCanGoToTheLocalExcludeWithoutDuplicates() throws IOException {
        write(".gitignore", "");
        commitAll("init");
        write("out/app.exe", "bin\n");

        git.ignorePattern("out/", true);
        git.ignorePattern("out/", true);

        assertThat(Files.readString(dir.resolve(".git/info/exclude"))).endsWith("out/\n");
        assertThat(Files.readString(dir.resolve(".gitignore"))).isEmpty();
        assertThat(git.status(true).entries()).anySatisfy(e -> {
            assertThat(e.path()).isEqualTo("out/app.exe");
            assertThat(e.kind()).isEqualTo(StatusEntry.Kind.IGNORED);
        });
    }

    @Test
    void stopTrackingKeepsTheFileOnDisk() throws IOException {
        write("data.txt", "1\n");
        commitAll("init");
        write("data.txt", "2\n");
        git.ignorePattern("data.txt", false);

        assertThat(git.ignoredTrackedFiles()).containsExactly("data.txt");

        git.stopTracking(List.of("data.txt"));

        assertThat(Files.readString(dir.resolve("data.txt"))).isEqualTo("2\n");
        assertThat(git.ignoredTrackedFiles()).isEmpty();
        assertThat(git.status(true).entries()).anySatisfy(e -> {
            assertThat(e.path()).isEqualTo("data.txt");
            assertThat(e.indexState()).isEqualTo('D');
            assertThat(e.isStaged()).isTrue();
        });
    }

    @Test
    void cleanPreviewAndCleanRemoveOnlyTheSelectedPaths() throws IOException {
        write("keep.txt", "k\n");
        commitAll("init");
        write("a.txt", "a\n");
        write("dir/b.txt", "b\n");

        List<String> preview = git.cleanPreview(false);
        assertThat(preview).contains("a.txt", "dir");

        git.clean(preview, false);

        assertThat(dir.resolve("a.txt")).doesNotExist();
        assertThat(dir.resolve("dir")).doesNotExist();
        assertThat(dir.resolve("keep.txt")).exists();
    }

    @Test
    void cleanHandlesMorePathsThanFitOnOneCommandLine() throws IOException {
        write("logs/keep.txt", "k\n");
        commitAll("init");
        int n = 1200;   // ~60 KiB of pathspecs, over the Windows command-line limit
        for (int i = 0; i < n; i++) {
            Files.writeString(dir.resolve(String.format("logs/untracked-%04d-file-with-a-long-name.txt", i)), "x");
        }

        List<String> preview = git.cleanPreview(false);
        assertThat(preview).hasSize(n);

        git.clean(preview, false);

        assertThat(dir.resolve("logs/keep.txt")).exists();
        try (var left = Files.list(dir.resolve("logs"))) {
            assertThat(left.map(p -> p.getFileName().toString()).toList()).containsExactly("keep.txt");
        }
    }

    @Test
    void cleanPreviewShowsTheRealNameOfQuotedAndSpacedFiles() throws IOException {
        write("keep.txt", "k\n");
        commitAll("init");
        write("città.txt", "c\n");
        write("file with spaces.txt", "s\n");

        assertThat(git.cleanPreview(false)).contains("città.txt", "file with spaces.txt");
    }

    @Test
    void ignoredFilesAreListedAndDeletedOnlyWithTheIncludeFlag() throws IOException {
        write("keep.txt", "k\n");
        commitAll("init");
        write("secret.log", "s\n");
        git.ignorePattern("*.log", false);

        assertThat(git.cleanPreview(false)).doesNotContain("secret.log");
        assertThat(git.cleanPreview(true)).contains("secret.log");
        assertThat(dir.resolve("secret.log")).exists();

        git.clean(git.cleanPreview(true), true);

        assertThat(dir.resolve("secret.log")).doesNotExist();
        assertThat(dir.resolve("keep.txt")).exists();
    }
}
