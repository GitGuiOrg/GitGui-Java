package com.github.gitguiorg.gitgui.ui.view;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class SyntaxHighlighterTest {
    private static String tokens(String path, String line) {
        StringBuilder sb = new StringBuilder();
        for (SyntaxHighlighter.Span s : SyntaxHighlighter.highlight(path, line)) {
            sb.append(s.style().substring(4)).append('[').append(line, s.start(), s.end()).append("] ");
        }
        return sb.toString().trim();
    }

    @Test
    void javaKeywordsStringsCommentsNumbers() {
        assertThat(tokens("A.java", "public int x = 42; // note"))
                .isEqualTo("kw[public] kw[int] num[42] com[// note]");
        assertThat(tokens("A.java", "String s = \"a // b\";")).isEqualTo("type[String] str[\"a // b\"]");
        assertThat(tokens("A.java", "foo(/* c */ 1)")).isEqualTo("com[/* c */] num[1]");
    }

    @Test
    void jsonKeysAndValues() {
        assertThat(tokens("a.json", "  \"name\": \"x\", \"n\": 1, \"ok\": true"))
                .isEqualTo("key[\"name\"] str[\"x\"] key[\"n\"] num[1] key[\"ok\"] kw[true]");
    }

    @Test
    void hashCommentsNeedBoundary() {
        assertThat(tokens("a.py", "x = 1  # hi")).isEqualTo("num[1] com[# hi]");
        assertThat(tokens("a.sh", "echo a#b")).isEqualTo("kw[echo]");
    }

    @Test
    void urlsInConfigValuesAreNotCommentsOrKeys() {
        assertThat(tokens("a.yml", "url: http://example.com/x")).isEqualTo("key[url]");
        assertThat(tokens("a.ini", "homepage = https://example.com")).isEqualTo("key[homepage]");
        // A real // comment still starts after code or blanks.
        assertThat(tokens("A.java", "x = 1; // note")).isEqualTo("num[1] com[// note]");
    }

    @Test
    void xmlTagsAndAttributes() {
        assertThat(tokens("p.xml", "<dep id=\"a\">")).isEqualTo("kw[<dep] str[\"a\"]");
        assertThat(tokens("p.xml", "<!-- c -->")).isEqualTo("com[<!-- c -->]");
    }

    @Test
    void extensionLessFilesAndGradleAreKnown() {
        assertThat(tokens("Dockerfile", "echo hi # c")).isEqualTo("kw[echo] com[# c]");
        assertThat(tokens("Makefile", "echo hi")).isEqualTo("kw[echo]");
        assertThat(tokens("build.gradle", "dependencies { // note")).isEqualTo("com[// note]");
    }

    @Test
    void unknownExtensionIsPlain() {
        assertThat(SyntaxHighlighter.supports("README")).isFalse();
        assertThat(SyntaxHighlighter.highlight("notes.txt", "public class")).isEqualTo(List.of());
    }
}
