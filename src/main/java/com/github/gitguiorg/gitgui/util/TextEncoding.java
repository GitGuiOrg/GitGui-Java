package com.github.gitguiorg.gitgui.util;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Text encoding detection for blobs, conflicted versions and diff bodies.
 * A byte order mark wins; otherwise a strictly valid UTF-8 sequence means UTF-8 and everything else
 * falls back to Windows-1252 (a superset of ISO-8859-1 that is the usual legacy encoding on Windows).
 */
public final class TextEncoding {
    private static final byte[] BOM_UTF8 = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final byte[] BOM_UTF16LE = {(byte) 0xFF, (byte) 0xFE};
    private static final byte[] BOM_UTF16BE = {(byte) 0xFE, (byte) 0xFF};
    private static final Charset WINDOWS_1252 = Charset.isSupported("windows-1252")
            ? Charset.forName("windows-1252") : StandardCharsets.ISO_8859_1;

    /** A decoded text with the encoding and BOM it came from, so it can be written back unchanged. */
    public record Decoded(String text, Charset charset, byte[] bom) {
        public byte[] encode(String value) {
            byte[] body = value.getBytes(charset);
            if (bom == null || bom.length == 0) return body;
            byte[] out = Arrays.copyOf(bom, bom.length + body.length);
            System.arraycopy(body, 0, out, bom.length, body.length);
            return out;
        }
    }

    private TextEncoding() {}

    /** Charset of the given bytes; never null. */
    public static Charset detect(byte[] data) {
        if (data == null || data.length == 0) return StandardCharsets.UTF_8;
        if (startsWith(data, BOM_UTF8)) return StandardCharsets.UTF_8;
        if (startsWith(data, BOM_UTF16LE)) return StandardCharsets.UTF_16LE;
        if (startsWith(data, BOM_UTF16BE)) return StandardCharsets.UTF_16BE;
        if (isValidUtf8(data, data.length)) return StandardCharsets.UTF_8;
        return WINDOWS_1252;
    }

    public static Decoded decode(byte[] data) {
        if (data == null || data.length == 0) return new Decoded("", StandardCharsets.UTF_8, new byte[0]);
        if (startsWith(data, BOM_UTF8)) return new Decoded(new String(data, 3, data.length - 3, StandardCharsets.UTF_8),
                StandardCharsets.UTF_8, BOM_UTF8);
        if (startsWith(data, BOM_UTF16LE)) return new Decoded(new String(data, 2, data.length - 2, StandardCharsets.UTF_16LE),
                StandardCharsets.UTF_16LE, BOM_UTF16LE);
        if (startsWith(data, BOM_UTF16BE)) return new Decoded(new String(data, 2, data.length - 2, StandardCharsets.UTF_16BE),
                StandardCharsets.UTF_16BE, BOM_UTF16BE);
        Charset cs = detect(data);
        return new Decoded(new String(data, cs), cs, new byte[0]);
    }

    public static String decodeToString(byte[] data) {
        return decode(data).text();
    }

    /** True when the bytes contain a UTF-16 byte order mark. */
    public static boolean hasUtf16Bom(byte[] data) {
        return data != null && (startsWith(data, BOM_UTF16LE) || startsWith(data, BOM_UTF16BE));
    }

    /** True when {@code data} is a valid UTF-8 sequence (a leading BOM is allowed). */
    public static boolean isValidUtf8(byte[] data) {
        return isValidUtf8(data, data == null ? 0 : data.length);
    }

    private static boolean isValidUtf8(byte[] data, int length) {
        if (length == 0) return true;
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data, 0, length));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (data[i] != prefix[i]) return false;
        return true;
    }
}
