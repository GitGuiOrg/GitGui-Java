package com.github.gitguiorg.gitgui.ui.view;

import com.github.gitguiorg.gitgui.model.ConflictMarkers;
import com.github.gitguiorg.gitgui.model.ConflictMarkers.Choice;
import com.github.gitguiorg.gitgui.model.Merge3.Chunk;
import com.github.gitguiorg.gitgui.ui.viewmodel.ResolverViewModel;
import com.github.gitguiorg.gitgui.ui.viewmodel.ResolverViewModel.Loaded;
import com.github.gitguiorg.gitgui.util.Texts;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.IntFunction;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

/** Three-way conflict resolver: Ours | Base | Theirs on top, editable Result (with diff3 markers) below. */
public final class ResolverView extends BorderPane {
    private final ResolverViewModel vm;
    private final Runnable onClose;
    private final Label count = new Label();
    private final Button mark = new Button("Mark resolved");
    private CodeArea result;
    private String eol = "\n";
    private boolean trailingNewline = true;
    private boolean restylePending;

    public ResolverView(ResolverViewModel vm, Runnable onClose) {
        this.vm = vm;
        this.onClose = onClose;
        setCenter(new Label("Loading…"));
        vm.loadedProperty().addListener((o, a, l) -> { if (l != null) build(l); });
        vm.load();
    }

    private void build(Loaded l) {
        if (l.text()) buildText(l); else buildStructural(l);
    }

    // ------------------------------------------------------------ non-text conflicts

    private void buildStructural(Loaded l) {
        Label msg = new Label("“" + vm.path() + "” cannot be merged line by line (binary file, or the file was added/deleted"
                + " on one side). Choose what to keep:");
        msg.setWrapText(true);
        HBox buttons = new HBox(10);
        buttons.setAlignment(Pos.CENTER);
        if (l.hasOurs()) {
            Button b = new Button("Keep our version");
            b.setOnAction(e -> vm.takeSide(true, onClose));
            buttons.getChildren().add(b);
        }
        if (l.hasTheirs()) {
            Button b = new Button("Keep their version");
            b.setOnAction(e -> vm.takeSide(false, onClose));
            buttons.getChildren().add(b);
        }
        Button del = new Button("Delete file");
        del.setStyle("-fx-text-fill: #f85149;");
        del.setOnAction(e -> vm.delete(onClose));
        buttons.getChildren().add(del);
        VBox box = new VBox(16, msg, buttons);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(40));
        box.setMaxWidth(560);
        BorderPane wrap = new BorderPane(box);
        BorderPane.setAlignment(box, Pos.CENTER);
        setTop(null);
        setCenter(wrap);
    }

    // ------------------------------------------------------------ text conflicts

    private void buildText(Loaded l) {
        eol = l.eol();
        trailingNewline = l.trailingNewline();

        BitSet oursHl = new BitSet(), baseHl = new BitSet(), theirsHl = new BitSet();
        for (Chunk c : l.chunks()) {
            if (!c.conflict()) continue;
            oursHl.set(c.oursStart(), c.oursStart() + c.ours().size());
            baseHl.set(c.baseStart(), c.baseStart() + c.base().size());
            theirsHl.set(c.theirsStart(), c.theirsStart() + c.theirs().size());
        }
        ResolverViewModel.Labels labels = vm.labels();
        SplitPane top = new SplitPane(
                pane(labels.oursPanel(), readOnly(l.ours(), oursHl)),
                pane("Base (common ancestor)", readOnly(l.base(), baseHl)),
                pane(labels.theirsPanel(), readOnly(l.theirs(), theirsHl)));

        result = new CodeArea();
        result.getStyleClass().add("diff-area");
        result.setParagraphGraphicFactory(LineNumberFactory.get(result));
        List<String> marked = com.github.gitguiorg.gitgui.model.Merge3.toMarkedLines(l.chunks());
        String text = String.join("\n", marked) + (trailingNewline && !marked.isEmpty() ? "\n" : "");
        result.replaceText(text);
        result.textProperty().addListener((o, a, b) -> scheduleRestyle());
        restyle();

        SplitPane center = new SplitPane(top, pane("Result (editable)", result));
        center.setOrientation(Orientation.VERTICAL);
        center.setDividerPositions(0.4);
        setTop(toolbar());
        setCenter(center);
        if (!ConflictMarkers.find(List.of(text.split("\n", -1))).isEmpty()) goTo(true);
    }

    private HBox toolbar() {
        Label file = new Label(vm.path());
        file.setStyle("-fx-font-weight: bold;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button prev = new Button("◀ Prev");
        prev.setOnAction(e -> goTo(false));
        Button next = new Button("Next ▶");
        next.setOnAction(e -> goTo(true));
        Button ours = choiceButton(vm.labels().oursAction(), Choice.OURS);
        Button theirs = choiceButton(vm.labels().theirsAction(), Choice.THEIRS);
        Button ot = choiceButton("Both (Ours, Theirs)", Choice.OURS_THEN_THEIRS);
        Button to = choiceButton("Both (Theirs, Ours)", Choice.THEIRS_THEN_OURS);
        Button base = choiceButton("Base", Choice.BASE);
        mark.getStyleClass().add("accent");
        mark.setOnAction(e -> markResolved());
        HBox bar = new HBox(8, file, spacer, count, prev, next, ours, theirs, ot, to, base, mark);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(6, 8, 6, 8));
        return bar;
    }

    private Button choiceButton(String text, Choice c) {
        Button b = new Button(text);
        b.setOnAction(e -> choose(c));
        return b;
    }

    private static BorderPane pane(String title, CodeArea area) {
        Label t = new Label(title);
        t.setStyle("-fx-font-weight: bold;");
        t.setPadding(new Insets(3, 8, 3, 8));
        return new BorderPane(new VirtualizedScrollPane<>(area), t, null, null, null);
    }

    private static CodeArea readOnly(List<String> lines, BitSet highlight) {
        CodeArea a = new CodeArea();
        a.setEditable(false);
        a.getStyleClass().add("diff-area");
        a.setParagraphGraphicFactory(LineNumberFactory.get(a));
        a.replaceText(String.join("\n", lines));
        applyLineStyles(a, lines, i -> highlight.get(i) ? "conf-line" : "plain-line");
        return a;
    }

    private static void applyLineStyles(CodeArea area, List<String> lines, IntFunction<String> style) {
        StyleSpansBuilder<Collection<String>> b = new StyleSpansBuilder<>();
        boolean any = false;
        for (int i = 0; i < lines.size(); i++) {
            int len = lines.get(i).length() + (i < lines.size() - 1 ? 1 : 0);
            if (len == 0) continue;
            b.add(Collections.singleton(style.apply(i)), len);
            any = true;
        }
        if (any) {
            StyleSpans<Collection<String>> spans = b.create();
            area.setStyleSpans(0, spans);
        }
    }

    // ------------------------------------------------------------ result editing

    private void scheduleRestyle() {
        if (restylePending) return;
        restylePending = true;
        Platform.runLater(() -> {
            restylePending = false;
            restyle();
        });
    }

    private void restyle() {
        List<String> lines = new ArrayList<>(List.of(result.getText().split("\n", -1)));
        String[] cls = new String[lines.size()];
        int state = 0;
        for (int i = 0; i < lines.size(); i++) {
            String s = lines.get(i);
            if (s.startsWith("<<<<<<<")) { state = 1; cls[i] = "res-marker"; }
            else if (state == 1 && s.startsWith("|||||||")) { state = 2; cls[i] = "res-marker"; }
            else if ((state == 1 || state == 2) && s.equals("=======")) { state = 3; cls[i] = "res-marker"; }
            else if (state == 3 && s.startsWith(">>>>>>>")) { state = 0; cls[i] = "res-marker"; }
            else cls[i] = state == 1 ? "res-ours" : state == 2 ? "res-base" : state == 3 ? "res-theirs" : "plain-line";
        }
        applyLineStyles(result, lines, i -> cls[i]);
        int n = ConflictMarkers.find(lines).size();
        boolean markers = ConflictMarkers.hasMarkers(lines);
        count.setText(n > 0 ? Texts.count(n, "conflict", "conflicts") + " left"
                : markers ? "Conflict markers left" : "No conflicts left");
        mark.setDisable(n > 0 || markers);
    }

    private List<String> resultLines() {
        return List.of(result.getText().split("\n", -1));
    }

    private void choose(Choice c) {
        List<String> lines = resultLines();
        List<ConflictMarkers.Block> blocks = ConflictMarkers.find(lines);
        if (blocks.isEmpty()) return;
        int caret = result.getCurrentParagraph();
        ConflictMarkers.Block b = blocks.stream().filter(x -> x.startLine() <= caret && caret <= x.endLine()).findFirst()
                .orElseGet(() -> blocks.stream().filter(x -> x.startLine() > caret).findFirst().orElse(blocks.get(0)));
        List<String> out = ConflictMarkers.resolve(lines, b, c);
        result.replaceText(String.join("\n", out));
        result.moveTo(Math.min(b.startLine(), Math.max(0, out.size() - 1)), 0);
        result.requestFollowCaret();
        result.requestFocus();
    }

    private void goTo(boolean forward) {
        List<ConflictMarkers.Block> blocks = ConflictMarkers.find(resultLines());
        if (blocks.isEmpty()) return;
        int caret = result.getCurrentParagraph();
        ConflictMarkers.Block target;
        if (forward) {
            target = blocks.stream().filter(x -> x.startLine() > caret).findFirst().orElse(blocks.get(0));
        } else {
            target = blocks.stream().filter(x -> x.startLine() < caret).reduce((a, b) -> b).orElse(blocks.get(blocks.size() - 1));
        }
        result.moveTo(target.startLine(), 0);
        result.requestFollowCaret();
    }

    private void markResolved() {
        // Complete blocks and stray/unclosed marker lines both mean the file is not resolved yet.
        if (ConflictMarkers.hasMarkers(resultLines())) {
            new Alert(Alert.AlertType.WARNING, "Resolve all conflict blocks first.").show();
            return;
        }
        String content = result.getText().replace("\r\n", "\n");
        if (trailingNewline && !content.isEmpty() && !content.endsWith("\n")) content += "\n";
        vm.markResolved(content.replace("\n", eol), onClose);
    }
}
