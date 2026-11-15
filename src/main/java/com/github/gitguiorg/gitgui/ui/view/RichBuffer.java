package com.github.gitguiorg.gitgui.ui.view;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

/** Accumulates the text and style spans of a diff pane, one paragraph per {@link #line} call. */
final class RichBuffer {
    private final StringBuilder text = new StringBuilder();
    private final StyleSpansBuilder<Collection<String>> spans = new StyleSpansBuilder<>();
    private int lines;
    private boolean hasSpans;

    int lines() { return lines; }

    String text() { return text.toString(); }

    /** @return null when no styled text was added */
    StyleSpans<Collection<String>> build() {
        return hasSpans ? spans.create() : null;
    }

    private void span(Collection<String> style, int len) {
        if (len <= 0) return;
        spans.add(style, len);
        hasSpans = true;
    }

    /**
     * Appends a paragraph.
     *
     * @param base   diff style of the whole line (diff-add, diff-ctx, ...)
     * @param prefix number of leading characters (the +/-/space marker) that are not source code
     * @param path   file the line belongs to, for syntax highlighting; null disables it
     */
    void line(String line, String base, int prefix, String path) {
        line(line, base, prefix, path, -1, -1);
    }

    /**
     * Appends a paragraph, adding {@code diff-word} to the given character range (inside the line) when it is
     * non-empty; used to highlight the changed words of paired removed/added lines.
     */
    void line(String line, String base, int prefix, String path, int changeStart, int changeEnd) {
        if (lines > 0) {
            text.append('\n');
            span(Collections.emptyList(), 1);
        }
        lines++;
        text.append(line);
        int n = line.length();
        int cs = changeStart < 0 ? -1 : Math.max(0, Math.min(n, changeStart));
        int ce = changeStart < 0 ? -1 : Math.max(0, Math.min(n, changeEnd));
        List<SyntaxHighlighter.Span> tokens = path == null || n <= prefix ? List.of()
                : SyntaxHighlighter.highlight(path, line.substring(prefix));

        List<Integer> bounds = new ArrayList<>(List.of(0, n));
        if (prefix > 0 && prefix < n) bounds.add(prefix);
        if (cs >= 0 && cs < ce) { bounds.add(cs); bounds.add(ce); }
        for (SyntaxHighlighter.Span t : tokens) {
            bounds.add(Math.min(n, prefix + t.start()));
            bounds.add(Math.min(n, prefix + t.end()));
        }
        bounds.sort(null);
        int prev = 0;
        for (int b : bounds) {
            if (b <= prev) continue;
            List<String> styles = new ArrayList<>(3);
            styles.add(base);
            for (SyntaxHighlighter.Span t : tokens) {
                if (prefix + t.start() <= prev && b <= prefix + t.end()) { styles.add(t.style()); break; }
            }
            if (cs >= 0 && prev >= cs && b <= ce) styles.add("diff-word");
            span(styles, b - prev);
            prev = b;
        }
    }
}
