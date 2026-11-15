package com.github.gitguiorg.gitgui.ui.view;

import com.github.gitguiorg.gitgui.git.PatchBuilder;
import com.github.gitguiorg.gitgui.model.DiffLine;
import com.github.gitguiorg.gitgui.model.FileDiff;
import com.github.gitguiorg.gitgui.model.Hunk;
import com.github.gitguiorg.gitgui.ui.viewmodel.ChangesViewModel;
import com.github.gitguiorg.gitgui.ui.viewmodel.DiffViewModel;
import com.github.gitguiorg.gitgui.ui.viewmodel.DiffViewModel.Content;
import com.github.gitguiorg.gitgui.ui.viewmodel.DiffViewModel.Mode;
import com.github.gitguiorg.gitgui.util.Texts;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.FileChooser;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;
import org.fxmisc.richtext.model.TwoDimensional.Bias;

/** Read-only unified diff with line numbers; stage/unstage/discard a hunk or the selected lines. */
public final class DiffView extends BorderPane {
    /** Used when the preference is not available yet (before the first render). */
    private static final int DEFAULT_MAX_ROWS = 20_000;

    /** Metadata of one rendered paragraph. {@code hunk}/{@code line} are -1 for file or hunk headers. */
    private record Row(int hunk, int line, boolean change, String oldNo, String newNo) {}

    private record FileMark(int paragraph, String path, boolean deleted) {}

    private final CodeArea area = new CodeArea();
    private final ChangesViewModel changes;
    private final Label title = new Label();
    private final Button primary = new Button();
    private final Button discard = new Button();
    private final DiffViewModel diffVm;
    private final VirtualizedScrollPane<CodeArea> unified;
    private final SideBySideView sideBySide;
    private final EmptyState empty;
    private final javafx.scene.layout.StackPane body = new javafx.scene.layout.StackPane();
    private final ToggleButton sbsToggle = new ToggleButton("Side by side");
    private final ToggleButton wsToggle = new ToggleButton("Ignore whitespace");
    private final ToggleButton syntaxToggle = new ToggleButton("Syntax");
    private final MenuButton patchMenu = new MenuButton("Patch");
    private final MenuButton optionsMenu = new MenuButton("Options");
    private final Label sbsNote = new Label("staging needs the inline view");
    private List<Row> rows = List.of();
    private List<FileMark> fileMarks = List.of();
    private Content current = Content.EMPTY;
    private String lastKey = "";

    public DiffView(DiffViewModel diff, ChangesViewModel changes) {
        this.changes = changes;
        this.diffVm = diff;
        this.sideBySide = new SideBySideView((path, deleted, x, y) -> fileMenu(path, deleted, this, x, y));
        area.setEditable(false);
        area.setWrapText(false);
        area.getStyleClass().add("diff-area");
        area.setParagraphGraphicFactory(this::lineNumbers);
        area.setOnContextMenuRequested(e -> {
            FileMark m = markAt(area.getCurrentParagraph());
            if (m == null) return;
            fileMenu(m.path(), m.deleted(), area, e.getScreenX(), e.getScreenY());
            e.consume();
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        sbsToggle.selectedProperty().bindBidirectional(diff.sideBySideProperty());
        wsToggle.selectedProperty().bindBidirectional(diff.ignoreWhitespaceProperty());
        syntaxToggle.selectedProperty().bindBidirectional(diff.syntaxProperty());
        sbsToggle.setTooltip(new javafx.scene.control.Tooltip("Show old and new text in two columns (read-only)"));
        wsToggle.setTooltip(new javafx.scene.control.Tooltip("Hide whitespace-only changes (staging is disabled meanwhile)"));
        syntaxToggle.setTooltip(new javafx.scene.control.Tooltip("Syntax highlighting"));
        MenuItem copyPatch = new MenuItem("Copy patch");
        copyPatch.setOnAction(e -> copyPatch());
        MenuItem savePatch = new MenuItem("Save patch…");
        savePatch.setOnAction(e -> savePatch());
        patchMenu.getItems().addAll(copyPatch, savePatch);
        patchMenu.setTooltip(new javafx.scene.control.Tooltip("Copy or save the shown diff, or only the selected hunks/lines"));
        CheckMenuItem ignoreCr = new CheckMenuItem("Ignore CR at end of line");
        ignoreCr.selectedProperty().bindBidirectional(diff.ignoreCrAtEolProperty());
        CheckMenuItem ignoreBlank = new CheckMenuItem("Ignore blank lines");
        ignoreBlank.selectedProperty().bindBidirectional(diff.ignoreBlankLinesProperty());
        Menu context = new Menu("Context lines");
        ToggleGroup contexts = new ToggleGroup();
        List<RadioMenuItem> contextItems = new ArrayList<>();
        for (int n : new int[]{0, 1, 3, 5, 10}) {
            RadioMenuItem item = new RadioMenuItem(Texts.count(n, "line", "lines"));
            item.setToggleGroup(contexts);
            item.setOnAction(e -> diff.contextLinesProperty().set(n));
            contextItems.add(item);
            context.getItems().add(item);
        }
        optionsMenu.getItems().addAll(ignoreCr, ignoreBlank, context);
        optionsMenu.setTooltip(new javafx.scene.control.Tooltip("Options that hide differences; staging is disabled while one is on"));
        optionsMenu.setOnShowing(e -> {
            int n = diff.contextLinesProperty().get();
            for (RadioMenuItem item : contextItems) {
                if (item.getText().startsWith(n + " ")) item.setSelected(true);
            }
        });
        sbsNote.setStyle("-fx-font-size: 11px; -fx-opacity: 0.6;");
        sbsNote.visibleProperty().bind(sbsToggle.selectedProperty());
        sbsNote.managedProperty().bind(sbsToggle.selectedProperty());
        // Buttons never collapse to "..."; when the pane is narrow they wrap onto a second line instead.
        javafx.scene.layout.FlowPane actions = new javafx.scene.layout.FlowPane(6, 3, sbsNote, primary, discard, patchMenu,
                sbsToggle, wsToggle, syntaxToggle, optionsMenu);
        actions.setAlignment(Pos.CENTER_RIGHT);
        for (var n : actions.getChildren()) {
            if (n instanceof javafx.scene.control.Control c) c.setMinWidth(Region.USE_PREF_SIZE);
        }
        title.setMinWidth(0);
        HBox.setHgrow(actions, Priority.SOMETIMES);
        HBox bar = new HBox(8, title, spacer, actions);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(3, 6, 3, 6));
        title.setStyle("-fx-font-weight: bold;");
        discard.setStyle("-fx-text-fill: #f85149;");
        setTop(bar);
        unified = new VirtualizedScrollPane<>(area);
        empty = new EmptyState("mdi2g-cursor-default-click-outline", "Nothing selected",
                "Select a commit or a file to see its diff.", null, null);
        body.getChildren().addAll(unified, sideBySide, empty);
        setCenter(body);

        primary.setOnAction(e -> apply(false));
        discard.setOnAction(e -> apply(true));
        area.selectionProperty().addListener((o, a, b) -> updateButtons());
        area.caretPositionProperty().addListener((o, a, b) -> updateButtons());
        diff.contentProperty().addListener((o, a, c) -> render(c));
        diff.sideBySideProperty().addListener((o, a, b) -> render(current));
        diff.syntaxProperty().addListener((o, a, b) -> render(current));
        render(diff.contentProperty().get());
    }

    private void fileMenu(String path, boolean deleted, javafx.scene.Node anchor, double x, double y) {
        Mode mode = current.mode();
        boolean commit = mode == Mode.COMMIT && current.rev() != null;
        boolean working = mode == Mode.UNSTAGED || mode == Mode.STAGED;
        boolean range = mode == Mode.RANGE;
        javafx.scene.control.MenuItem edit = new javafx.scene.control.MenuItem("Open in editor");
        edit.setDisable(deleted || !(working || mode == Mode.UNTRACKED));
        edit.setOnAction(a -> changes.openInEditor(path));
        javafx.scene.control.MenuItem blame = new javafx.scene.control.MenuItem("Blame");
        blame.setDisable(!((commit && !deleted) || working));
        blame.setOnAction(a -> changes.openBlame(path, commit ? current.rev() : null));
        javafx.scene.control.MenuItem hist = new javafx.scene.control.MenuItem("File history");
        hist.setDisable(!(commit || working || range));
        hist.setOnAction(a -> changes.openFileHistory(path));
        new javafx.scene.control.ContextMenu(edit, blame, hist).show(anchor.getScene().getWindow(), x, y);
    }

    // ------------------------------------------------------------ rendering

    private int maxRows() {
        int n = diffVm.maxLinesProperty().get();
        return n > 0 ? n : DEFAULT_MAX_ROWS;
    }

    private javafx.scene.Node lineNumbers(int par) {
        Row r = par < rows.size() ? rows.get(par) : null;
        Label old = new Label(r == null ? "" : r.oldNo());
        Label neu = new Label(r == null ? "" : r.newNo());
        for (Label l : List.of(old, neu)) {
            l.setMinWidth(44);
            l.setAlignment(Pos.CENTER_RIGHT);
            l.setStyle("-fx-text-fill: #6e7681; -fx-font-family: 'Consolas', monospace; -fx-font-size: 12px;");
        }
        HBox box = new HBox(6, old, neu);
        box.setPadding(new Insets(0, 8, 0, 4));
        return box;
    }

    private void render(Content c) {
        String key = c.mode() + "|" + c.title();
        double scroll = key.equals(lastKey) ? area.getEstimatedScrollY() : 0;
        lastKey = key;
        current = c;
        title.setText(c.title() + (c.ignoresDifferences() ? "   (differences ignored)" : ""));
        boolean syntax = diffVm.syntaxProperty().get();
        boolean none = c.mode() == Mode.NONE;

        if (diffVm.sideBySideProperty().get() && !none) {
            sideBySide.show(c, syntax, maxRows());
            showOnly(sideBySide);
            rows = List.of();
            fileMarks = List.of();
            updateButtons();
            return;
        }
        showOnly(none ? empty : unified);

        RichBuffer buf = new RichBuffer();
        List<Row> newRows = new ArrayList<>();
        List<FileMark> marks = new ArrayList<>();

        outer:
        for (FileDiff f : c.files()) {
            String path = syntax ? f.path() : null;
            if (c.mode() == Mode.COMMIT || c.mode() == Mode.RANGE || newRows.isEmpty()) {
                String head = f.path() + (f.newFile() ? "  (new file)" : f.deletedFile() ? "  (deleted)" : "")
                        + (f.oldPath() != null && f.newPath() != null && !f.oldPath().equals(f.newPath())
                        && !f.newFile() && !f.deletedFile() ? "  (renamed from " + f.oldPath() + ")" : "");
                marks.add(new FileMark(newRows.size(), f.path(), f.deletedFile()));
                add(buf, newRows, head, "diff-file", null, new Row(-1, -1, false, "", ""));
            }
            if (f.binary()) {
                add(buf, newRows, "Binary file not shown", "diff-meta", null, new Row(-1, -1, false, "", ""));
                continue;
            }
            if (f.lfsPointer()) {
                add(buf, newRows, "Git LFS pointer - the content is stored in Git LFS, not in this diff",
                        "diff-meta", null, new Row(-1, -1, false, "", ""));
                continue;
            }
            for (int h = 0; h < f.hunks().size(); h++) {
                Hunk hunk = f.hunks().get(h);
                add(buf, newRows, hunk.header(), "diff-hunk", null, new Row(h, -1, false, "…", "…"));
                int o = hunk.oldStart();
                int n = hunk.newStart();
                for (int l = 0; l < hunk.lines().size(); l++) {
                    if (newRows.size() >= maxRows()) {
                        add(buf, newRows, "… diff truncated", "diff-meta", null, new Row(-1, -1, false, "", ""));
                        break outer;
                    }
                    DiffLine dl = hunk.lines().get(l);
                    String t = Texts.stripCr(dl.render());
                    switch (dl.type()) {
                        case CONTEXT -> add(buf, newRows, t, "diff-ctx", path, new Row(h, l, false, "" + o++, "" + n++));
                        case ADDED -> add(buf, newRows, t, "diff-add", path, new Row(h, l, true, "", "" + n++));
                        case REMOVED -> add(buf, newRows, t, "diff-del", path, new Row(h, l, true, "" + o++, ""));
                        case NO_NEWLINE -> add(buf, newRows, t, "diff-meta", null, new Row(h, l, false, "", ""));
                    }
                }
            }
        }
        if (c.mode() != Mode.NONE && newRows.isEmpty()) {
            add(buf, newRows, "No differences", "diff-meta", null, new Row(-1, -1, false, "", ""));
        }

        rows = newRows;
        fileMarks = marks;
        area.replaceText(buf.text());
        var spans = buf.build();
        if (spans != null) area.setStyleSpans(0, spans);
        area.moveTo(0);
        if (scroll > 0) Platform.runLater(() -> area.scrollYToPixel(scroll));
        updateButtons();
    }

    private static void add(RichBuffer buf, List<Row> rows, String line, String style, String syntaxPath, Row row) {
        buf.line(line, style, 1, syntaxPath);
        rows.add(row);
    }

    /** Keeps a single child of the body visible, so the empty state can replace the diff without losing it. */
    private void showOnly(javafx.scene.Node node) {
        for (var child : body.getChildren()) {
            boolean on = child == node;
            child.setVisible(on);
            child.setManaged(on);
        }
    }

    private FileMark markAt(int paragraph) {
        FileMark found = null;
        for (FileMark m : fileMarks) if (m.paragraph() <= paragraph) found = m;
        return found;
    }

    // ------------------------------------------------------------ actions

    /** hunk index → selected change-line indexes; a null value means the whole hunk. */
    private Map<Integer, Set<Integer>> selectedOps() {
        Map<Integer, Set<Integer>> ops = new HashMap<>();
        if (!(current.editable() && !diffVm.sideBySideProperty().get()) || rows.isEmpty()) return ops;
        var sel = area.getSelection();
        if (sel.getLength() == 0) {
            int p = area.getCurrentParagraph();
            if (p < rows.size() && rows.get(p).hunk() >= 0) ops.put(rows.get(p).hunk(), null);
            return ops;
        }
        int p1 = area.offsetToPosition(sel.getStart(), Bias.Forward).getMajor();
        int p2 = area.offsetToPosition(sel.getEnd(), Bias.Backward).getMajor();
        for (int p = p1; p <= p2 && p < rows.size(); p++) {
            Row r = rows.get(p);
            if (r.hunk() >= 0 && r.line() >= 0 && r.change()) ops.computeIfAbsent(r.hunk(), k -> new HashSet<>()).add(r.line());
        }
        return ops;
    }

    private void updateButtons() {
        Map<Integer, Set<Integer>> ops = selectedOps();
        boolean on = !ops.isEmpty();
        boolean partial = area.getSelection().getLength() > 0;
        int lines = ops.values().stream().filter(s -> s != null).mapToInt(Set::size).sum();
        String unit = partial ? Texts.count(lines, "line", "lines") : "hunk";
        Mode m = current.mode();
        primary.setVisible((current.editable() && !diffVm.sideBySideProperty().get()));
        primary.setManaged((current.editable() && !diffVm.sideBySideProperty().get()));
        primary.setText((m == Mode.STAGED ? "Unstage " : "Stage ") + unit);
        primary.setDisable(!on);
        boolean canDiscard = m == Mode.UNSTAGED;
        discard.setVisible(canDiscard);
        discard.setManaged(canDiscard);
        discard.setText("Discard " + unit);
        discard.setDisable(!on);
        patchMenu.setDisable(current.files().isEmpty() || current.ignoresDifferences());
    }

    // ------------------------------------------------------------ patch export

    /** Whole diff, or only the selected hunks/lines when the file can be staged. */
    private String currentPatch() {
        StringBuilder patch = new StringBuilder();
        if (current.editable() && current.files().size() == 1) {
            FileDiff f = current.files().get(0);
            Map<Integer, Set<Integer>> ops = selectedOps();
            if (ops.isEmpty()) for (int i = 0; i < f.hunks().size(); i++) ops.put(i, null);
            PatchBuilder.buildFile(f, ops).ifPresent(patch::append);
            return patch.toString();
        }
        for (FileDiff f : current.files()) PatchBuilder.buildFile(f, null).ifPresent(patch::append);
        return patch.toString();
    }

    private void copyPatch() {
        String patch = currentPatch();
        if (patch.isEmpty()) return;
        ClipboardContent cc = new ClipboardContent();
        cc.putString(patch);
        Clipboard.getSystemClipboard().setContent(cc);
    }

    private void savePatch() {
        String patch = currentPatch();
        if (patch.isEmpty()) return;
        FileChooser fc = new FileChooser();
        fc.setTitle("Save patch");
        fc.setInitialFileName(patchName());
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Patch files", "*.patch", "*.diff"));
        File target = fc.showSaveDialog(getScene() == null ? null : getScene().getWindow());
        if (target == null) return;
        try {
            Files.writeString(target.toPath(), patch);
        } catch (IOException e) {
            new Alert(Alert.AlertType.ERROR, "Cannot save the patch: " + e.getMessage(), ButtonType.OK).show();
        }
    }

    private String patchName() {
        if (current.files().size() == 1) {
            String p = current.files().get(0).path();
            if (p != null && !p.isBlank()) {
                Path name = Path.of(p).getFileName();
                if (name != null) return name + ".patch";
            }
        }
        return "changes.patch";
    }

    private void apply(boolean discardIt) {
        Map<Integer, Set<Integer>> ops = selectedOps();
        if (ops.isEmpty()) return;
        FileDiff f = current.files().get(0);
        if (discardIt) {
            Alert a = new Alert(Alert.AlertType.CONFIRMATION, "Discard the selected changes? This cannot be undone.",
                    ButtonType.OK, ButtonType.CANCEL);
            if (a.showAndWait().filter(b -> b == ButtonType.OK).isEmpty()) return;
            changes.discardHunks(f, ops);
        } else if (current.mode() == Mode.STAGED) {
            changes.unstageHunks(f, ops);
        } else {
            changes.stageHunks(f, ops);
        }
    }
}
