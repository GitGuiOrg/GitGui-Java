package com.github.gitguiorg.gitgui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.gitguiorg.gitgui.git.DiffParser;
import com.github.gitguiorg.gitgui.git.GitClient;
import com.github.gitguiorg.gitgui.git.GitCommand;
import com.github.gitguiorg.gitgui.git.GitException;
import com.github.gitguiorg.gitgui.git.GitRunner;
import com.github.gitguiorg.gitgui.model.FileDiff;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitToolingTest {
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

    /** git runs hooks only if they are executable: on POSIX the bit is required, Windows ignores it. */
    void makeExecutable(Path p) throws IOException {
        if (Files.getFileStore(p).supportsFileAttributeView(java.nio.file.attribute.PosixFileAttributeView.class)) {
            Files.setPosixFilePermissions(p, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    @Test
    void commandsAreRecordedAndReportedToListeners() throws Exception {
        write("f.txt", "one\n");
        git.stage(List.of("f.txt"));
        git.status(false);

        List<GitRunner.CommandRecord> history = git.commandHistory();
        assertThat(history).isNotEmpty();
        GitRunner.CommandRecord last = history.get(history.size() - 1);
        assertThat(last.commandLine()).contains("status");
        assertThat(last.exitCode()).isZero();
        assertThat(last.durationMs()).isGreaterThanOrEqualTo(0);
        assertThat(last.at()).isNotNull();
        assertThat(last.dir().toRealPath()).isEqualTo(dir.toRealPath());

        List<GitRunner.CommandRecord> seen = new ArrayList<>();
        git.addCommandListener(seen::add);
        git.status(false);
        assertThat(seen).hasSize(1);
        assertThat(seen.get(0).commandLine()).contains("status");

        git.clearCommandHistory();
        assertThat(git.commandHistory()).isEmpty();
    }

    @Test
    void hookFailureCanBeSkippedExplicitly() throws Exception {
        write(".git/hooks/pre-commit", "#!/bin/sh\nexit 1\n");
        makeExecutable(dir.resolve(".git/hooks/pre-commit"));
        write("f.txt", "one\n");
        git.stage(List.of("f.txt"));
        assertThat(git.commitHookPresent()).isTrue();

        assertThatThrownBy(() -> git.commit("blocked", false)).isInstanceOf(GitException.class);
        assertThat(git.log(0, 5, false)).isEmpty();

        git.commit("allowed", false, null, null, false, true);
        assertThat(git.log(0, 1, false).get(0).subject()).isEqualTo("allowed");
    }

    @Test
    void lfsPointerIsRecognisedInADiff() {
        String pointer = String.join("\n",
                "diff --git a/big.bin b/big.bin",
                "new file mode 100644",
                "index 0000000..1111111",
                "--- /dev/null",
                "+++ b/big.bin",
                "@@ -0,0 +1,3 @@",
                "+version https://git-lfs.github.com/spec/v1",
                "+oid sha256:0123456789abcdef",
                "+size 1234",
                "");
        List<FileDiff> files = DiffParser.parse(pointer);
        assertThat(files).hasSize(1);
        assertThat(files.get(0).lfsPointer()).isTrue();

        String normal = String.join("\n",
                "diff --git a/a.txt b/a.txt",
                "--- a/a.txt",
                "+++ b/a.txt",
                "@@ -1 +1 @@",
                "-old",
                "+new",
                "");
        assertThat(DiffParser.parse(normal).get(0).lfsPointer()).isFalse();
    }

    @Test
    void maintenanceCommandsRun() throws Exception {
        write("f.txt", "one\n");
        git.stage(List.of("f.txt"));
        git.commit("first", false);

        git.gc();
        git.prune();
        assertThat(git.fsck()).isNotBlank();
    }

    @Test
    void noHookMeansNothingToSkip() throws Exception {
        write("f.txt", "one\n");
        git.stage(List.of("f.txt"));
        assertThat(git.commitHookPresent()).isFalse();
    }
}
