package com.github.gitguiorg.gitgui.ui.view;

import com.github.gitguiorg.gitgui.model.DiffLine;
import com.github.gitguiorg.gitgui.model.FileDiff;
import com.github.gitguiorg.gitgui.model.Hunk;
import com.github.gitguiorg.gitgui.ui.viewmodel.DiffViewModel.Content;
import com.github.gitguiorg.gitgui.ui.viewmodel.DiffViewModel.Mode;
import com.github.gitguiorg.gitgui.util.Texts;
import java.util.ArrayList;
import java.util.List;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;

/** Read-only two-column diff: old text on the left, new text on the right, scrolled together. */
final class SideBySideView extends HBox {
    /** Called when the user asks for the file menu: path, deleted?, screen x, screen y. */
    interface FileMenuHandler {
        void show(String path, boolean deleted, double x, double y);
    }

    private record FileMark(int paragraph, String path, boolean deleted) {}

    /** Mutable state while building both columns. */
    private static final class Build {
        final RichBuffer left = new RichBuffer();
        final RichBuffer right = new RichBuffer();
        final List<String> leftNums = new ArrayList<>();
        final List<String> rightNums = new ArrayList<>();
        final List<DiffLine> removed = new ArrayList<>();
        final List<DiffLine> added = new ArrayList<>();
        int oldNo;
        int newNo;

        void both(String text, String style) {
            left.line(text, style, 0, null);
            right.line(text, style, 0, null);
            leftNums.add("");
            rightNums.add("");
        }

        /** Emits the pending removed/added lines side by side, padding the shorter column. */
        void flush(String path) {
            int rows = Math.max(removed.size(), added.size());
            for (int i = 0; i < rows; i++) {
                String before = i < removed.size() ? Texts.stripCr(removed.get(i).text()) : null;
                String after = i < added.size() ? Texts.stripCr(added.get(i).text()) : null;
                if (before != null) {
                    int[] w = after == null ? null : changedRange(before, after);
                    if (w == null) left.line(before, "diff-del", 0, path);
                    else left.line(before, "diff-del", 0, path, w[0], w[1]);
                    leftNums.add("" + oldNo++);
                } else {
                    left.line("", "diff-pad", 0, null);
                    leftNums.add("");
                }
                if (after != null) {
                    int[] w = before == null ? null : changedRange(after, before);
                    if (w == null) right.line(after, "diff-add", 0, path);
                    else right.line(after, "diff-add", 0, path, w[0], w[1]);
                    rightNums.add("" + newNo++);
                } else {
                    right.line("", "diff-pad", 0, null);
                    rightNums.add("");
                }
            }
            removed.clear();
            added.clear();
        }
    }

    /**
     * Range of {@code line} that actually changed compared with {@code other} (common prefix and suffix removed).
     * Null when the two lines share the same non-blank text or one of them is blank.
     */
    static int[] changedRange(String line, String other) {
        int n = line.length();
        int m = other.length();
        if (n == 0 || m == 0) return null;
        int prefix = 0;
        while (prefix < n && prefix < m && line.charAt(prefix) == other.charAt(prefix)) prefix++;
        int suffix = 0;
        while (suffix < n - prefix && suffix < m - prefix
                && line.charAt(n - 1 - suffix) == other.charAt(m - 1 - suffix)) suffix++;
        int end = n - suffix;
        // never cut a surrogate pair in half: some emoji share the high surrogate, so the prefix can stop inside one
        if (prefix > 0 && prefix < n && Character.isLowSurrogate(line.charAt(prefix))
                && Character.isHighSurrogate(line.charAt(prefix - 1))) prefix--;
        if (end > 0 && end < n && Character.isHighSurrogate(line.charAt(end - 1))
                && Character.isLowSurrogate(line.charAt(end))) end++;
        if (end <= prefix) return null;
        if (line.substring(prefix, end).isBlank()) return null;
        return new int[]{prefix, end};
    }

    private final CodeArea left = new CodeArea();
    private final CodeArea right = new CodeArea();
    private List<String> leftNums = List.of();
    private List<String> rightNums = List.of();
    private List<FileMark> marks = List.of();
    private boolean syncing;

    SideBySideView(FileMenuHandler menu) {
        for (CodeArea a : List.of(left, right)) {
            a.setEditable(false);
            a.setWrapText(false);
            a.getStyleClass().add("diff-area");
        }
        left.setParagraphGraphicFactory(p -> number(leftNums, p));
        right.setParagraphGraphicFactory(p -> number(rightNums, p));
        VirtualizedScrollPane<CodeArea> lp = new VirtualizedScrollPane<>(left);
        VirtualizedScrollPane<CodeArea> rp = new VirtualizedScrollPane<>(right);
        lp.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        HBox.setHgrow(lp, Priority.ALWAYS);
        HBox.setHgrow(rp, Priority.ALWAYS);
        lp.setMinWidth(0);
        rp.setMinWidth(0);
        lp.prefWidthProperty().bind(widthProperty().divide(2));
        rp.prefWidthProperty().bind(widthProperty().divide(2));
        getChildren().addAll(lp, rp);
        link(left, right);
        link(right, left);
        for (CodeArea a : List.of(left, right)) {
            a.setOnContextMenuRequested(e -> {
                FileMark m = markAt(a.getCurrentParagraph());
                if (m == null) return;
                menu.show(m.path(), m.deleted(), e.getScreenX(), e.getScreenY());
                e.consume();
            });
        }
    }

    private void link(CodeArea from, CodeArea to) {
        from.estimatedScrollYProperty().addListener((o, a, y) -> {
            if (syncing) return;
            syncing = true;
            to.scrollYToPixel(y);
            syncing = false;
        });
        from.estimatedScrollXProperty().addListener((o, a, x) -> {
            if (syncing) return;
            syncing = true;
            to.scrollXToPixel(x);
            syncing = false;
        });
    }

    private FileMark markAt(int paragraph) {
        FileMark found = null;
        for (FileMark m : marks) if (m.paragraph() <= paragraph) found = m;
        return found;
    }

    private static javafx.scene.Node number(List<String> nums, int par) {
        Label l = new Label(par < nums.size() ? nums.get(par) : "");
        l.setMinWidth(44);
        l.setAlignment(Pos.CENTER_RIGHT);
        l.setStyle("-fx-text-fill: #6e7681; -fx-font-family: 'Consolas', monospace; -fx-font-size: 12px;");
        HBox box = new HBox(l);
        box.setPadding(new Insets(0, 8, 0, 4));
        return box;
    }

    void show(Content c, boolean syntax, int maxRows) {
        Build b = new Build();
        List<FileMark> fm = new ArrayList<>();

        outer:
        for (FileDiff f : c.files()) {
            String path = syntax ? f.path() : null;
            String head = f.path() + (f.newFile() ? "  (new file)" : f.deletedFile() ? "  (deleted)" : "");
            fm.add(new FileMark(b.left.lines(), f.path(), f.deletedFile()));
            b.both(head, "diff-file");
            if (f.binary()) {
                b.both("Binary file not shown", "diff-meta");
                continue;
            }
            if (f.lfsPointer()) {
                b.both("Git LFS pointer - the content is stored in Git LFS, not in this diff", "diff-meta");
                continue;
            }
            for (Hunk h : f.hunks()) {
                b.both(h.header(), "diff-hunk");
                b.oldNo = h.oldStart();
                b.newNo = h.newStart();
                for (DiffLine dl : h.lines()) {
                    if (b.left.lines() >= maxRows) {
                        b.flush(path);
                        b.both("… diff truncated", "diff-meta");
                        break outer;
                    }
                    switch (dl.type()) {
                        case REMOVED -> b.removed.add(dl);
                        case ADDED -> b.added.add(dl);
                        case CONTEXT -> {
                            b.flush(path);
                            String t = Texts.stripCr(dl.text());
                            b.left.line(t, "diff-ctx", 0, path);
                            b.right.line(t, "diff-ctx", 0, path);
                            b.leftNums.add("" + b.oldNo++);
                            b.rightNums.add("" + b.newNo++);
                        }
                        case NO_NEWLINE -> {
                            b.flush(path);
                            b.both(Texts.stripCr(dl.render()), "diff-meta");
                        }
                    }
                }
                b.flush(path);
            }
        }
        if (b.left.lines() == 0 && c.mode() != Mode.NONE) b.both("No differences", "diff-meta");

        leftNums = b.leftNums;
        rightNums = b.rightNums;
        marks = fm;
        fill(left, b.left);
        fill(right, b.right);
    }

    private static void fill(CodeArea area, RichBuffer buf) {
        area.replaceText(buf.text());
        var spans = buf.build();
        if (spans != null) area.setStyleSpans(0, spans);
        area.moveTo(0);
        area.scrollYToPixel(0);
        area.scrollXToPixel(0);
    }
}
