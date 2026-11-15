package com.github.gitguiorg.gitgui.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitRunnerLocateTest {
    @TempDir Path tmp;

    /** A fake git inside a folder with a space, as the usual Windows install path. */
    private Path fakeGit() throws IOException {
        Path dir = tmp.resolve("Program Files").resolve("Git").resolve("cmd");
        Files.createDirectories(dir);
        Path exe = dir.resolve("git.exe");
        Files.writeString(exe, "");
        return exe;
    }

    @Test
    void findsGitWhenThePathEntryIsQuoted() throws Exception {
        Path exe = fakeGit();
        String path = "\"" + exe.getParent() + "\"" + File.pathSeparator + tmp.resolve("nope");

        assertThat(GitRunner.locate(null, path)).isEqualTo(exe.toString());
    }

    @Test
    void findsGitInAPlainEntryAndIgnoresEmptyOnes() throws Exception {
        Path exe = fakeGit();
        String path = File.pathSeparator + "   " + File.pathSeparator + exe.getParent() + File.pathSeparator + "";

        assertThat(GitRunner.locate(null, path)).isEqualTo(exe.toString());
    }

    @Test
    void anEmptyPathEnvIsNotAFoundGit() {
        assertThatThrownBy(() -> GitRunner.locate(null, null)).isInstanceOf(GitException.class);
        assertThatThrownBy(() -> GitRunner.locate(null, "")).isInstanceOf(GitException.class);
        assertThatThrownBy(() -> GitRunner.locate(null, tmp.resolve("missing").toString()))
                .isInstanceOf(GitException.class)
                .hasMessageContaining("not found in PATH");
    }

    @Test
    void anExplicitPathWinsAndIsChecked() throws Exception {
        Path exe = fakeGit();

        assertThat(GitRunner.locate("\"" + exe + "\"", "")).isEqualTo(exe.toString());
        assertThat(GitRunner.locate("  " + exe + "  ", null)).isEqualTo(exe.toString());
        assertThatThrownBy(() -> GitRunner.locate(exe + ".bak", null))
                .isInstanceOf(GitException.class)
                .hasMessageContaining("not found");
    }
}
