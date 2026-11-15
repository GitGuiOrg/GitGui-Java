package com.github.gitguiorg.gitgui.ui.view;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SideBySideWordTest {
    @Test
    void highlightsOnlyTheChangedMiddle() {
        assertThat(SideBySideView.changedRange("hello world", "hello there")).containsExactly(6, 11);
        assertThat(SideBySideView.changedRange("abcXYZdef", "abcQdef")).containsExactly(3, 6);
    }

    @Test
    void changedRangeNeverSplitsASurrogatePair() {
        // U+1F600 and U+1F601 share the high surrogate D83D, so the common prefix stops inside the emoji
        assertThat(SideBySideView.changedRange("\uD83D\uDE00", "\uD83D\uDE01")).containsExactly(0, 2);
        assertThat(SideBySideView.changedRange("x\uD83D\uDE00y", "x\uD83D\uDE01y")).containsExactly(1, 3);
    }

    @Test
    void identicalOrWhitespaceOnlyLinesHaveNoRange() {
        assertThat(SideBySideView.changedRange("same", "same")).isNull();
        assertThat(SideBySideView.changedRange("a  b", "a    b")).isNull();
        assertThat(SideBySideView.changedRange("", "text")).isNull();
        assertThat(SideBySideView.changedRange("   ", "  ")).isNull();
    }
}
