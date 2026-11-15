package com.github.gitguiorg.gitgui.ui.viewmodel;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UntrackedDiscardTest {
    @TempDir Path tmp;

    @Test
    void deletesAWholeUntrackedDirectory() throws Exception {
        Path dir = tmp.resolve("nuova");
        Files.createDirectories(dir.resolve("sub"));
        Files.writeString(dir.resolve("a.txt"), "a");
        Files.writeString(dir.resolve("sub/b.txt"), "b");

        ChangesViewModel.deleteUntracked(dir);

        assertThat(dir).doesNotExist();
    }

    @Test
    void deletesAFileAndToleratesAMissingPath() throws Exception {
        Path file = tmp.resolve("f.txt");
        Files.writeString(file, "x");

        ChangesViewModel.deleteUntracked(file);
        assertThat(file).doesNotExist();

        ChangesViewModel.deleteUntracked(file);   // already gone: no error
    }

    @Test
    void doesNotFollowASymlinkedDirectory() throws Exception {
        Path real = tmp.resolve("real");
        Files.createDirectories(real);
        Files.writeString(real.resolve("keep.txt"), "keep");
        Path link = tmp.resolve("link");
        try {
            Files.createSymbolicLink(link, real);
        } catch (UnsupportedOperationException | IOException e) {
            return;   // Windows without the symlink privilege
        }

        ChangesViewModel.deleteUntracked(link);

        assertThat(link).doesNotExist();
        assertThat(real.resolve("keep.txt")).exists();
    }
}
