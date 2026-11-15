package com.github.gitguiorg.gitgui.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GitPathTest {
    @Test
    void decodesOctalUtf8Bytes() {
        assertThat(GitPath.unquote("\"a/citt\\303\\240.txt\"")).isEqualTo("a/città.txt");
    }

    @Test
    void decodesControlCharactersAndQuotes() {
        assertThat(GitPath.unquote("\"a/tab\\there.txt\"")).isEqualTo("a/tab\there.txt");
        assertThat(GitPath.unquote("\"a/quote\\\"x\"")).isEqualTo("a/quote\"x");
        assertThat(GitPath.unquote("\"a/back\\\\slash\"")).isEqualTo("a/back\\slash");
    }

    @Test
    void leavesUnquotedPathsAlone() {
        assertThat(GitPath.unquote("a/plain.txt")).isEqualTo("a/plain.txt");
        assertThat(GitPath.unquote(null)).isNull();
        assertThat(GitPath.unquote("\"unterminated")).isEqualTo("\"unterminated");
    }
}
