package com.github.gitguiorg.gitgui.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ByteSizesTest {
    @Test
    void bytesBelowOneUnitArePlain() {
        assertThat(ByteSizes.format(0)).isEqualTo("0 B");
        assertThat(ByteSizes.format(512)).isEqualTo("512 B");
        assertThat(ByteSizes.format(1023)).isEqualTo("1023 B");
    }

    @Test
    void largerSizesUseOneDecimal() {
        assertThat(ByteSizes.format(1024)).isEqualTo("1.0 KB");
        assertThat(ByteSizes.format(1536)).isEqualTo("1.5 KB");
        assertThat(ByteSizes.format(1024L * 1024)).isEqualTo("1.0 MB");
        assertThat(ByteSizes.format(1024L * 1024 * 1024)).isEqualTo("1.0 GB");
        assertThat(ByteSizes.format(1024L * 1024 * 1024 * 1024)).isEqualTo("1.0 TB");
    }

    @Test
    void roundingNeverShowsAThousandAndTwentyFourOfALowerUnit() {
        assertThat(ByteSizes.format(1024L * 1024 - 1)).isEqualTo("1.0 MB");
        assertThat(ByteSizes.format(1024L * 1024 * 1024 - 1)).isEqualTo("1.0 GB");
        assertThat(ByteSizes.format(1024L * 1024 - 102)).isEqualTo("1023.9 KB");
    }

    @Test
    void unknownSizeIsEmpty() {
        assertThat(ByteSizes.format(-1)).isEmpty();
    }
}
