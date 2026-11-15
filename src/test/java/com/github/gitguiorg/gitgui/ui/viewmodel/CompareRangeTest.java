package com.github.gitguiorg.gitgui.ui.viewmodel;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CompareRangeTest {
    private static final String SHA1 = "0123456789abcdef0123456789abcdef01234567";
    private static final String SHA256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void fullObjectIdsAreAbbreviatedWhateverTheirLength() {
        assertThat(new CompareViewModel.Range(SHA1, null).label()).isEqualTo("0123456 vs working tree");
        assertThat(new CompareViewModel.Range(SHA256, SHA1).label()).isEqualTo("0123456 vs 0123456");
    }

    @Test
    void branchNamesTagsAndExpressionsAreLeftAlone() {
        assertThat(new CompareViewModel.Range("feature/long-name", "main").label())
                .isEqualTo("feature/long-name vs main");
        assertThat(new CompareViewModel.Range("HEAD~2", "v1.2.3").label())
                .isEqualTo("HEAD~2 vs v1.2.3");
        assertThat(new CompareViewModel.Range(null, null).label()).isEqualTo(" vs working tree");
    }
}
