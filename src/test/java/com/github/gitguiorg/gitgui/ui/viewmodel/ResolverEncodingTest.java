package com.github.gitguiorg.gitgui.ui.viewmodel;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.gitguiorg.gitgui.model.ConflictVersions;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ResolverEncodingTest {

    @Test
    void utf16FilesAreTextAndKeepTheirBom() {
        byte[] ours = withBom("riga nostra\n");
        byte[] theirs = withBom("riga loro\n");
        ResolverViewModel.Loaded loaded = ResolverViewModel.build(new ConflictVersions(null, ours, theirs));
        assertThat(loaded.text()).isTrue();
        assertThat(loaded.ours()).containsExactly("riga nostra");
        assertThat(loaded.theirs()).containsExactly("riga loro");
        byte[] resolved = loaded.encoding().encode("unito\n");
        assertThat(resolved[0]).isEqualTo((byte) 0xFF);
        assertThat(resolved[1]).isEqualTo((byte) 0xFE);
        assertThat(new String(resolved, 2, resolved.length - 2, StandardCharsets.UTF_16LE)).isEqualTo("unito\n");
    }

    @Test
    void windows1252ContentIsDecodedAndWrittenBackUnchanged() {
        byte[] latin1 = "caf\u00e9\n".getBytes(StandardCharsets.ISO_8859_1);
        ResolverViewModel.Loaded loaded = ResolverViewModel.build(new ConflictVersions(null, latin1, latin1));
        assertThat(loaded.text()).isTrue();
        assertThat(loaded.ours()).containsExactly("caf\u00e9");
        assertThat(loaded.encoding().encode("caf\u00e9\n")).isEqualTo(latin1);
    }

    @Test
    void realBinaryFilesStayBinary() {
        byte[] binary = {0, 1, 2, 'a'};
        ResolverViewModel.Loaded loaded = ResolverViewModel.build(new ConflictVersions(null, binary, binary));
        assertThat(loaded.text()).isFalse();
    }

    private static byte[] withBom(String s) {
        byte[] body = s.getBytes(StandardCharsets.UTF_16LE);
        byte[] out = new byte[body.length + 2];
        out[0] = (byte) 0xFF;
        out[1] = (byte) 0xFE;
        System.arraycopy(body, 0, out, 2, body.length);
        return out;
    }
}
