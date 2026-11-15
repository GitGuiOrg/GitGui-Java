package com.github.gitguiorg.gitgui;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.gitguiorg.gitgui.git.DiffParser;
import com.github.gitguiorg.gitgui.model.FileContent;
import com.github.gitguiorg.gitgui.model.FileDiff;
import com.github.gitguiorg.gitgui.util.TextEncoding;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class EncodingTest {

    @Test
    void detectsBomUtf8AndLegacyBytes() {
        assertThat(TextEncoding.detect("Caf\u00e9".getBytes(StandardCharsets.UTF_8))).isEqualTo(StandardCharsets.UTF_8);
        assertThat(TextEncoding.detect("\u00e9".getBytes(StandardCharsets.ISO_8859_1)))
                .isEqualTo(java.nio.charset.Charset.forName("windows-1252"));
        assertThat(TextEncoding.detect(new byte[]{(byte) 0xFF, (byte) 0xFE, 'a', 0})).isEqualTo(StandardCharsets.UTF_16LE);
        assertThat(TextEncoding.hasUtf16Bom(new byte[]{(byte) 0xFE, (byte) 0xFF, 0, 'a'})).isTrue();
    }

    @Test
    void decodeAndEncodeKeepTheBom() {
        byte[] utf8Bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'h', 'i'};
        TextEncoding.Decoded d = TextEncoding.decode(utf8Bom);
        assertThat(d.text()).isEqualTo("hi");
        assertThat(d.encode("hello")).startsWith((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);

        byte[] utf16 = "hello".getBytes(StandardCharsets.UTF_16LE);
        byte[] withBom = new byte[utf16.length + 2];
        withBom[0] = (byte) 0xFF;
        withBom[1] = (byte) 0xFE;
        System.arraycopy(utf16, 0, withBom, 2, utf16.length);
        TextEncoding.Decoded u = TextEncoding.decode(withBom);
        assertThat(u.text()).isEqualTo("hello");
        assertThat(u.encode("hello")).isEqualTo(withBom);
    }

    @Test
    void fileContentUsesTheDetectedEncoding() {
        FileContent latin1 = new FileContent(4, false, false, "Caf\u00e9".getBytes(StandardCharsets.ISO_8859_1));
        assertThat(latin1.text()).isEqualTo("Caf\u00e9");
        FileContent utf8 = new FileContent(5, false, false, "Caf\u00e9".getBytes(StandardCharsets.UTF_8));
        assertThat(utf8.text()).isEqualTo("Caf\u00e9");
    }

    @Test
    void diffBodyIsDecodedPerFile() {
        String diff = "diff --git a/f.txt b/f.txt\n"
                + "--- a/f.txt\n+++ b/f.txt\n"
                + "@@ -1 +1 @@\n"
                + "-caf\u00e9\n"
                + "+Caf\u00e9\n";
        byte[] raw = diff.getBytes(StandardCharsets.ISO_8859_1);
        List<FileDiff> files = DiffParser.parse(raw);
        assertThat(files).hasSize(1);
        assertThat(files.get(0).hunks()).hasSize(1);
        assertThat(files.get(0).hunks().get(0).lines().get(0).text()).isEqualTo("caf\u00e9");
        assertThat(files.get(0).hunks().get(0).lines().get(1).text()).isEqualTo("Caf\u00e9");
    }

    @Test
    void utf8DiffStillParses() {
        String diff = "diff --git a/f.txt b/f.txt\n"
                + "--- a/f.txt\n+++ b/f.txt\n"
                + "@@ -1 +1 @@\n"
                + "-citt\u00e0\n"
                + "+citt\u00e0 nuova\n";
        List<FileDiff> files = DiffParser.parse(diff);
        assertThat(files.get(0).hunks().get(0).lines().get(1).text()).isEqualTo("citt\u00e0 nuova");
    }
}
