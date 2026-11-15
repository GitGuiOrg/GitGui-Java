package com.github.gitguiorg.gitgui.git;

import com.github.gitguiorg.gitgui.model.DiffLine;
import com.github.gitguiorg.gitgui.model.FileDiff;
import com.github.gitguiorg.gitgui.model.Hunk;
import com.github.gitguiorg.gitgui.util.GitPath;
import com.github.gitguiorg.gitgui.util.TextEncoding;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses unified diffs as produced by git into {@link FileDiff}s.
 * Parsing happens on the raw bytes: every file's hunk body is decoded with the encoding detected for that file
 * (BOM, valid UTF-8, otherwise Windows-1252), so legacy encodings are not mangled. UTF-16 files are not supported
 * here because line splitting is byte based; their preview and the conflict resolver do handle them.
 */
public final class DiffParser {
    private static final Pattern HUNK = Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@(.*)$");
    private static final byte[] DIFF = {'d', 'i', 'f', 'f', ' '};
    private static final byte[] HUNK_START = {'@', '@'};
    private static final int SAMPLE_LIMIT = 16 * 1024;

    private DiffParser() {}

    public static List<FileDiff> parse(String text) {
        return parse(text.getBytes(StandardCharsets.UTF_8));
    }

    /** Parses a unified diff from git's raw stdout bytes. */
    public static List<FileDiff> parse(byte[] data) {
        List<FileDiff> files = new ArrayList<>();
        List<byte[]> lines = splitLines(data);
        int n = lines.size();
        int i = 0;
        while (i < n) {
            if (!startsWith(lines.get(i), DIFF)) { i++; continue; }
            List<String> header = new ArrayList<>();
            header.add(utf8(lines.get(i++)));
            while (i < n && !startsWith(lines.get(i), DIFF) && !startsWith(lines.get(i), HUNK_START)) {
                header.add(utf8(lines.get(i++)));
            }

            List<Hunk> hunks = new ArrayList<>();
            while (i < n && startsWith(lines.get(i), HUNK_START)) {
                Matcher m = HUNK.matcher(utf8(lines.get(i++)));
                int bodyStart = i;
                while (i < n && !startsWith(lines.get(i), DIFF) && !startsWith(lines.get(i), HUNK_START)) i++;
                if (!m.matches()) continue;
                Charset charset = detectBodyCharset(lines, bodyStart, i);
                List<DiffLine> body = new ArrayList<>();
                for (int k = bodyStart; k < i; k++) {
                    // The trailing CR is kept: PatchBuilder has to write it back for files stored with CRLF in the
                    // repository, or git apply rejects the patch. Views strip it for display through Texts.stripCr.
                    byte[] line = lines.get(k);
                    if (line.length == 0) continue;
                    DiffLine.Type type = switch (line[0]) {
                        case ' ' -> DiffLine.Type.CONTEXT;
                        case '+' -> DiffLine.Type.ADDED;
                        case '-' -> DiffLine.Type.REMOVED;
                        case '\\' -> DiffLine.Type.NO_NEWLINE;
                        default -> null;
                    };
                    if (type != null) body.add(new DiffLine(type, new String(line, 1, line.length - 1, charset)));
                }
                hunks.add(new Hunk(Integer.parseInt(m.group(1)), count(m.group(2)),
                        Integer.parseInt(m.group(3)), count(m.group(4)), m.group(5), body));
            }
            files.add(build(header, hunks));
        }
        return files;
    }

    /** Encoding of one file's hunk body, sampled from context, added and removed lines. */
    private static Charset detectBodyCharset(List<byte[]> lines, int from, int to) {
        byte[] sample = new byte[SAMPLE_LIMIT];
        int length = 0;
        for (int k = from; k < to && length < SAMPLE_LIMIT; k++) {
            byte[] line = stripCr(lines.get(k));
            if (line.length < 2) continue;
            if (line[0] != ' ' && line[0] != '+' && line[0] != '-') continue;
            int copy = Math.min(line.length - 1, SAMPLE_LIMIT - length);
            System.arraycopy(line, 1, sample, length, copy);
            length += copy;
            if (length < SAMPLE_LIMIT) sample[length++] = '\n';
        }
        if (length == 0) return StandardCharsets.UTF_8;
        // The 16 KiB cut can fall inside a multibyte character; an incomplete tail would make the whole
        // sample look like invalid UTF-8 and decode the hunk as Windows-1252.
        length = completeUtf8Tail(sample, length);
        if (length == 0) return StandardCharsets.UTF_8;
        Charset cs = TextEncoding.detect(Arrays.copyOf(sample, length));
        return cs == StandardCharsets.UTF_16LE || cs == StandardCharsets.UTF_16BE ? StandardCharsets.UTF_8 : cs;
    }

    /** Length of {@code data} without a trailing, incomplete UTF-8 sequence (a valid tail is kept whole). */
    private static int completeUtf8Tail(byte[] data, int length) {
        int i = length - 1;
        int continuations = 0;
        while (i >= 0 && (data[i] & 0xC0) == 0x80 && continuations < 3) {
            i--;
            continuations++;
        }
        if (i < 0) return length;
        int lead = data[i] & 0xFF;
        int needed = lead < 0x80 ? 1 : lead >= 0xF0 ? 4 : lead >= 0xE0 ? 3 : lead >= 0xC0 ? 2 : 1;
        return needed > 1 + continuations ? i : length;
    }

    private static List<byte[]> splitLines(byte[] data) {
        List<byte[]> lines = new ArrayList<>();
        if (data == null || data.length == 0) return lines;
        int start = 0;
        for (int i = 0; i < data.length; i++) {
            if (data[i] == '\n') {
                lines.add(Arrays.copyOfRange(data, start, i));
                start = i + 1;
            }
        }
        if (start < data.length) lines.add(Arrays.copyOfRange(data, start, data.length));
        return lines;
    }

    private static byte[] stripCr(byte[] line) {
        if (line.length > 0 && line[line.length - 1] == '\r') return Arrays.copyOf(line, line.length - 1);
        return line;
    }

    private static boolean startsWith(byte[] line, byte[] prefix) {
        if (line.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (line[i] != prefix[i]) return false;
        return true;
    }

    private static String utf8(byte[] line) {
        return new String(line, StandardCharsets.UTF_8);
    }

    private static int count(String s) {
        return s == null ? 1 : Integer.parseInt(s);
    }

    private static FileDiff build(List<String> header, List<Hunk> hunks) {
        String oldPath = null, newPath = null, renameFrom = null, renameTo = null;
        boolean binary = false, isNew = false, deleted = false;
        for (String h : header) {
            if (h.startsWith("--- ")) oldPath = stripPrefix(h.substring(4), "a/");
            else if (h.startsWith("+++ ")) newPath = stripPrefix(h.substring(4), "b/");
            else if (h.startsWith("rename from ")) renameFrom = GitPath.unquote(h.substring(12));
            else if (h.startsWith("rename to ")) renameTo = GitPath.unquote(h.substring(10));
            else if (h.startsWith("new file mode")) isNew = true;
            else if (h.startsWith("deleted file mode")) deleted = true;
            else if (h.startsWith("Binary files") || h.startsWith("GIT binary patch")) binary = true;
        }
        if (oldPath == null && newPath == null) {
            String first = header.get(0);
            // Binary diffs have no ---/+++ lines: the two paths in "diff --git" are all we get.
            // When they are the same, their common length also works for unquoted paths with spaces.
            if (first.startsWith("diff --git a/")) {
                String rest = first.substring("diff --git ".length());
                int length = (rest.length() - 5) / 2;
                if (length > 0 && rest.length() == 2 * length + 5 && rest.charAt(2 + length) == ' '
                        && rest.startsWith("b/", 3 + length)) {
                    oldPath = rest.substring(2, 2 + length);
                    newPath = rest.substring(5 + length);
                } else {
                    int idx = first.indexOf(" b/", 13);
                    if (idx > 0) {
                        oldPath = first.substring(13, idx);
                        newPath = first.substring(idx + 3);
                    }
                }
            } else if (first.startsWith("diff --git \"")) {
                String[] paths = diffGitQuotedPaths(first);
                if (paths != null) {
                    oldPath = stripPrefix(paths[0], "a/");
                    newPath = stripPrefix(paths[1], "b/");
                }
            }
        }
        if (renameFrom != null) oldPath = renameFrom;
        if (renameTo != null) newPath = renameTo;
        if (isNew && oldPath == null) oldPath = "/dev/null";
        if (deleted && newPath == null) newPath = "/dev/null";
        return new FileDiff(List.copyOf(header), oldPath, newPath, binary, isNew, deleted, List.copyOf(hunks));
    }

    private static String stripPrefix(String s, String prefix) {
        int tab = s.indexOf('\t');
        if (tab >= 0) s = s.substring(0, tab);
        s = GitPath.unquote(s);
        return s.startsWith(prefix) ? s.substring(prefix.length()) : s;
    }

    /** The two quoted paths of {@code diff --git "a/…" "b/…"}, or null when the line is not in that form. */
    private static String[] diffGitQuotedPaths(String line) {
        String rest = line.substring("diff --git ".length());
        List<String> paths = new ArrayList<>(2);
        int i = 0;
        while (i < rest.length() && paths.size() < 2) {
            if (rest.charAt(i) != '"') return null;
            int end = i + 1;
            while (end < rest.length()) {
                char c = rest.charAt(end);
                if (c == '\\') end += 2;
                else if (c == '"') break;
                else end++;
            }
            if (end >= rest.length()) return null;
            paths.add(GitPath.unquote(rest.substring(i, end + 1)));
            i = end + 1;
            if (i < rest.length()) {
                if (rest.charAt(i) != ' ') return null;
                i++;
            }
        }
        return paths.size() == 2 && i == rest.length() ? paths.toArray(String[]::new) : null;
    }
}
