package com.github.gitguiorg.gitgui.util;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Paths as git prints them in diff headers: a path with unusual bytes is wrapped in double quotes and
 * every non-ASCII byte is octal-escaped, e.g. {@code "a/citt\303\240.txt"} for {@code a/città.txt}.
 */
public final class GitPath {
    private GitPath() {}

    /** The path in {@code token} with its C-style escapes decoded; unquoted tokens come back unchanged. */
    public static String unquote(String token) {
        if (token == null) return null;
        if (token.length() < 2 || !token.startsWith("\"") || !token.endsWith("\"")) return token;

        String body = token.substring(1, token.length() - 1);
        ByteArrayOutputStream out = new ByteArrayOutputStream(body.length());
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c != '\\' || i + 1 >= body.length()) {
                int cp = body.codePointAt(i);
                out.writeBytes(new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8));
                i += Character.charCount(cp) - 1;
                continue;
            }
            char esc = body.charAt(i + 1);
            switch (esc) {
                case 'a' -> { out.write(7); i++; }
                case 'b' -> { out.write(8); i++; }
                case 'f' -> { out.write(12); i++; }
                case 'n' -> { out.write(10); i++; }
                case 'r' -> { out.write(13); i++; }
                case 't' -> { out.write(9); i++; }
                case 'v' -> { out.write(11); i++; }
                case '"', '\\' -> { out.write(esc); i++; }
                default -> {
                    if (esc >= '0' && esc <= '7') {
                        int value = 0;
                        int digits = 0;
                        int j = i + 1;
                        while (j < body.length() && digits < 3 && body.charAt(j) >= '0' && body.charAt(j) <= '7') {
                            value = value * 8 + (body.charAt(j) - '0');
                            j++;
                            digits++;
                        }
                        out.write(value);
                        i = j - 1;
                    } else {
                        out.write(esc);   // unknown escape: keep the character, drop the backslash
                        i++;
                    }
                }
            }
        }
        return TextEncoding.decodeToString(out.toByteArray());
    }
}
