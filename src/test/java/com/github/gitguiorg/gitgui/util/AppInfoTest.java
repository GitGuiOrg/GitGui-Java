package com.github.gitguiorg.gitgui.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AppInfoTest {
    @Test
    void versionIsFilteredFromTheBuild() {
        assertThat(AppInfo.VERSION).isNotBlank().doesNotContain("${");
    }
}
