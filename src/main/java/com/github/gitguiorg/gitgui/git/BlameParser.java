package com.github.gitguiorg.gitgui.git;

import com.github.gitguiorg.gitgui.model.BlameLine;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Parses {@code git blame --porcelain}: a header per line, commit metadata only on first occurrence, then TAB + text. */
public final class BlameParser {
    private static final Pattern HEADER = Pattern.compile("^[0-9a-f]{40,64} \\d+ \\d+( \\d+)?$");

    private BlameParser() {}

    public static List<BlameLine> parse(String out) {
        Map<String, String[]> meta = new HashMap<>();   // hash -> {author, author-time, summary}
        List<BlameLine> result = new ArrayList<>();
        String hash = null;
        int finalLine = 0;
        for (String line : out.split("\n", -1)) {
            if (line.startsWith("\t")) {
                if (hash == null) continue;
                String[] m = meta.get(hash);
                result.add(new BlameLine(finalLine, hash, m[0], Instant.ofEpochSecond(Long.parseLong(m[1])), m[2], line.substring(1)));
            } else if (HEADER.matcher(line).matches()) {
                String[] parts = line.split(" ");
                hash = parts[0];
                finalLine = Integer.parseInt(parts[2]);
                meta.computeIfAbsent(hash, k -> new String[]{"", "0", ""});
            } else if (hash != null) {
                String[] m = meta.get(hash);
                if (line.startsWith("author ")) m[0] = line.substring(7);
                else if (line.startsWith("author-time ")) m[1] = line.substring(12).trim();
                else if (line.startsWith("summary ")) m[2] = line.substring(8);
            }
        }
        return result;
    }
}
