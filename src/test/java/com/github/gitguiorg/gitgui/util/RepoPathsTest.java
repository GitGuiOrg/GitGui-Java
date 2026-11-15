package com.github.gitguiorg.gitgui.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class RepoPathsTest {
    @Test
    void keysAreAbsoluteAndNormalized() {
        Path base = Path.of("build", "repo").toAbsolutePath();
        assertThat(RepoPaths.key(base.resolve("sub/.."))).isEqualTo(RepoPaths.key(base));
        assertThat(RepoPaths.same(base, base.resolve("."))).isTrue();
        assertThat(RepoPaths.keyOrNull(null)).isNull();
        assertThat(RepoPaths.keyOrNull("a\u0000b")).isNull();
    }

    @Test
    void windowsKeysIgnoreCase() {
        Path base = Path.of("build", "repo").toAbsolutePath();
        String other = base.toString().toUpperCase(Locale.ROOT);
        if (File.separatorChar == '\\') {
            assertThat(RepoPaths.same(base, Path.of(other))).isTrue();
        } else {
            assertThat(RepoPaths.same(base, Path.of(other))).isFalse();
        }
    }
}
