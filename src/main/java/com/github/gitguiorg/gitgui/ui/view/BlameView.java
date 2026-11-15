package com.github.gitguiorg.gitgui.ui.view;

import com.github.gitguiorg.gitgui.model.BlameLine;
import com.github.gitguiorg.gitgui.ui.viewmodel.BlameViewModel;
import com.github.gitguiorg.gitgui.ui.viewmodel.RepoViewModel;
import com.github.gitguiorg.gitgui.util.Hashes;
import com.github.gitguiorg.gitgui.util.Texts;
import java.util.List;
import java.util.stream.Collectors;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;

/** Code with a per-line annotation column (hash, author, date); lines of one commit share a tint. */
public final class BlameView extends BorderPane {
    private static final String[] TINTS = {
            "rgba(79,195,247,0.16)", "rgba(186,104,200,0.16)", "rgba(129,199,132,0.16)", "rgba(255,183,77,0.16)",
            "rgba(229,115,115,0.16)", "rgba(77,182,172,0.16)", "rgba(240,98,146,0.16)", "rgba(174,213,129,0.16)"};

    private final RepoViewModel repo;
    private final CodeArea area = new CodeArea();
    private List<BlameLine> lines = List.of();

    public BlameView(BlameViewModel vm, RepoViewModel repo) {
        this.repo = repo;
        area.setEditable(false);
        area.setWrapText(false);
        area.getStyleClass().add("diff-area");
        area.setParagraphGraphicFactory(this::annotation);

        Label file = new Label(vm.path() + (vm.rev() == null ? "" : "  @ " + Hashes.shortHash(vm.rev())));
        file.setStyle("-fx-font-weight: bold;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button history = new Button("File history");
        history.setOnAction(e -> repo.openFileHistory(vm.path()));
        Button reload = new Button("Reload");
        reload.setOnAction(e -> vm.load());
        HBox bar = new HBox(8, file, spacer, history, reload);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(6, 8, 6, 8));
        setTop(bar);
        setCenter(new VirtualizedScrollPane<>(area));

        vm.linesProperty().addListener((o, a, l) -> { if (l != null) show(l); });
        vm.load();
    }

    private void show(List<BlameLine> l) {
        lines = l;
        area.replaceText(l.stream().map(x -> Texts.stripCr(x.text()))
                .collect(Collectors.joining("\n")));
    }

    private Node annotation(int par) {
        if (par >= lines.size()) return new Region();
        BlameLine l = lines.get(par);
        boolean first = par == 0 || !lines.get(par - 1).hash().equals(l.hash());
        boolean uncommitted = l.uncommitted();

        Label no = label(String.valueOf(l.line()), 40, Pos.CENTER_RIGHT, "#6e7681");
        Label hash = label(first ? (uncommitted ? "—" : Hashes.shortHash(l.hash())) : "", 62, Pos.CENTER_LEFT, "#79c0ff");
        hash.setStyle(hash.getStyle() + " -fx-font-family: 'Consolas', monospace;");
        Label author = label(first ? (uncommitted ? "Not committed" : l.author()) : "", 130, Pos.CENTER_LEFT, "#c9d1d9");
        Label date = label(first && !uncommitted ? com.github.gitguiorg.gitgui.util.DateFormats.date(l.date()) : "",
                84, Pos.CENTER_LEFT, "#8b949e");

        HBox box = new HBox(6, no, hash, author, date);
        int tint = Math.floorMod(l.hash().hashCode(), TINTS.length);
        box.setStyle("-fx-background-color: " + (uncommitted ? "rgba(110,118,129,0.12)" : TINTS[tint]) + "; -fx-padding: 0 6 0 4;");
        if (!uncommitted) {
            box.setCursor(Cursor.HAND);
            box.setOnMouseClicked(e -> repo.diff().showCommitByHash(l.hash(), l.summary()));
            if (first) {
                Tooltip.install(box, new Tooltip(l.summary() + "\n" + l.author() + "  "
                        + com.github.gitguiorg.gitgui.util.DateFormats.dateTime(l.date()) + "\n" + l.hash()));
            }
        }
        return box;
    }

    private static Label label(String text, double width, Pos align, String color) {
        Label l = new Label(text);
        l.setMinWidth(width);
        l.setPrefWidth(width);
        l.setMaxWidth(width);
        l.setAlignment(align);
        l.setStyle("-fx-text-fill: " + color + "; -fx-font-size: 12px;");
        return l;
    }
}
