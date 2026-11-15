package com.github.gitguiorg.gitgui.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TextsTest {
    @Test
    void ellipsisOnlyCutsLongerText() {
        assertThat(Texts.ellipsis("abcdef", 3)).isEqualTo("abc…");
        assertThat(Texts.ellipsis("abc", 3)).isEqualTo("abc");
        assertThat(Texts.ellipsis("abc", 10)).isEqualTo("abc");
        assertThat(Texts.ellipsis("abc", 0)).isEmpty();
        assertThat(Texts.ellipsis(null, 10)).isEmpty();
    }

    @Test
    void ellipsisCountsCodePointsAndKeepsSurrogatePairsWhole() {
        assertThat(Texts.ellipsis("aa😀b", 3)).isEqualTo("aa😀…");
        assertThat(Texts.ellipsis("😀😀", 2)).isEqualTo("😀😀");
    }

    @Test
    void stripCrRemovesOnlyATrailingCarriageReturn() {
        assertThat(Texts.stripCr("line\r")).isEqualTo("line");
        assertThat(Texts.stripCr("line")).isEqualTo("line");
        assertThat(Texts.stripCr("a\rb")).isEqualTo("a\rb");
        assertThat(Texts.stripCr(null)).isEmpty();
    }

    @Test
    void codePointLengthCountsVisibleCharacters() {
        assertThat(Texts.codePointLength("abc")).isEqualTo(3);
        assertThat(Texts.codePointLength("a😀b")).isEqualTo(3);
        assertThat(Texts.codePointLength("")).isZero();
        assertThat(Texts.codePointLength(null)).isZero();
    }

    @Test
    void countUsesTheSingularOnlyForOne() {
        assertThat(Texts.count(0, "file", "files")).isEqualTo("0 files");
        assertThat(Texts.count(1, "file", "files")).isEqualTo("1 file");
        assertThat(Texts.count(2, "file", "files")).isEqualTo("2 files");
    }

    @Test
    void firstLineSkipsLeadingBlanksAndIsCut() {
        assertThat(Texts.firstLine("\n\nfatal: something failed\nmore", 120)).isEqualTo("fatal: something failed");
        assertThat(Texts.firstLine("  short  ", 120)).isEqualTo("short");
        assertThat(Texts.firstLine("x".repeat(150), 120)).isEqualTo("x".repeat(120) + "…");
        assertThat(Texts.firstLine("   ", 120)).isEmpty();
        assertThat(Texts.firstLine(null, 120)).isEmpty();
    }
}
