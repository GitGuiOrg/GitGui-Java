package com.github.gitguiorg.gitgui.ui.view;

import com.github.gitguiorg.gitgui.model.Commit;
import com.github.gitguiorg.gitgui.model.FileContent;
import com.github.gitguiorg.gitgui.model.TreeEntry;
import com.github.gitguiorg.gitgui.ui.Fx;
import com.github.gitguiorg.gitgui.ui.viewmodel.RepoViewModel;
import com.github.gitguiorg.gitgui.util.ByteSizes;
import java.io.File;
import java.util.List;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.FileChooser;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;

/** Folder tree of one commit with a read-only preview of the selected file, without checking it out. */
public final class CommitFilesView extends BorderPane {
    private static final int MAX_BYTES = 2_000_000;
    private static final int MAX_ROWS = 5_000;

    private final Commit commit;
    private final RepoViewModel repo;
    private final CodeArea preview = new CodeArea();
    private final Label title = new Label("Select a file");
    private final Button save = new Button("Save as…");
    private final Button copy = new Button("Copy path");
    private final Button blame = new Button("Blame");
    private final Button history = new Button("History");
    private TreeEntry selected;

    public CommitFilesView(Commit commit, RepoViewModel repo) {
        this.commit = commit;
        this.repo = repo;
        preview.setEditable(false);
        preview.setWrapText(false);
        preview.getStyleClass().add("diff-area");

        TreeView<TreeEntry> tree = new TreeView<>();
        tree.setShowRoot(false);
        tree.setCellFactory(tv -> new TreeCell<>() {
            @Override
            protected void updateItem(TreeEntry e, boolean empty) {
                super.updateItem(e, empty);
                if (empty || e == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                String suffix = e.submodule() ? "  (submodule)" : e.directory() ? "/" : "";
                setText(e.name() + suffix);
            }
        });
        TreeItem<TreeEntry> root = new TreeItem<>(null);
        tree.setRoot(root);
        loadChildren(root, "");
        root.setExpanded(true);
        tree.getSelectionModel().selectedItemProperty().addListener((o, a, item) -> {
            TreeEntry e = item == null ? null : item.getValue();
            if (e == null) return;
            if (e.directory()) {
                selected = null;
                updateButtons();
                title.setText(e.path() + "/");
                preview.replaceText("");
                return;
            }
            selected = e;
            show(e);
        });

        for (Button b : List.of(save, copy, blame, history)) b.setDisable(true);
        save.setOnAction(e -> saveAs());
        copy.setOnAction(e -> {
            ClipboardContent cc = new ClipboardContent();
            cc.putString(selected.path());
            Clipboard.getSystemClipboard().setContent(cc);
        });
        blame.setOnAction(e -> repo.openBlame(selected.path(), commit.hash()));
        history.setOnAction(e -> repo.openFileHistory(selected.path()));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(6, title, spacer, save, copy, blame, history);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(2, 6, 2, 6));
        title.setStyle("-fx-font-weight: bold;");

        SplitPane split = new SplitPane(tree, new VirtualizedScrollPane<>(preview));
        split.setOrientation(Orientation.HORIZONTAL);
        split.setDividerPositions(0.28);
        setTop(bar);
        setCenter(split);
    }

    private void loadChildren(TreeItem<TreeEntry> parent, String path) {
        repo.session().async(() -> repo.session().git().listTree(commit.hash(), path))
                .whenComplete((list, err) -> Fx.run(() -> {
                    if (err != null) { repo.reportError(err); return; }
                    parent.getChildren().setAll(list.stream().map(e -> {
                        TreeItem<TreeEntry> item = new TreeItem<>(e);
                        if (e.directory()) {
                            item.getChildren().add(new TreeItem<>(null));
                            item.expandedProperty().addListener((o, a, expanded) -> {
                                if (expanded && item.getChildren().size() == 1 && item.getChildren().get(0).getValue() == null) {
                                    item.getChildren().clear();
                                    loadChildren(item, e.path());
                                }
                            });
                        }
                        return item;
                    }).toList());
                }));
    }

    private void show(TreeEntry e) {
        title.setText(e.path() + (e.size() >= 0 ? "  ·  " + ByteSizes.format(e.size()) : ""));
        updateButtons();
        if (e.submodule()) {
            blame.setDisable(true);
            message("Submodule  ·  " + e.path() + "\nThe recorded commit is checked out from another repository.");
            return;
        }
        repo.session().async(() -> repo.session().git().fileContent(commit.hash(), e.path(), MAX_BYTES))
                .whenComplete((c, err) -> Fx.run(() -> {
                    if (err != null) { repo.reportError(err); return; }
                    if (selected != e) return;
                    render(e, c);
                }));
    }

    private void updateButtons() {
        boolean file = selected != null;
        save.setDisable(!file);
        copy.setDisable(!file);
        blame.setDisable(!file);
        history.setDisable(!file);
    }

    private void render(TreeEntry e, FileContent c) {
        if (c.binary()) {
            message("Binary file  ·  " + ByteSizes.format(c.size()));
            return;
        }
        if (c.truncated()) {
            message("File too large to preview  ·  " + ByteSizes.format(c.size()) + "\nUse \"Save as…\" to open it with another program.");
            return;
        }
        boolean syntax = repo.diff().syntaxProperty().get();
        String[] lines = c.text().replace("\r\n", "\n").split("\n", -1);
        RichBuffer buf = new RichBuffer();
        int n = Math.min(lines.length, MAX_ROWS);
        for (int i = 0; i < n; i++) buf.line(lines[i], "diff-ctx", 0, syntax ? e.path() : null);
        if (lines.length > MAX_ROWS) buf.line("… preview truncated (" + lines.length + " lines)", "diff-meta", 0, null);
        preview.replaceText(buf.text());
        var spans = buf.build();
        if (spans != null) preview.setStyleSpans(0, spans);
        preview.moveTo(0);
    }

    private void message(String text) {
        RichBuffer buf = new RichBuffer();
        for (String line : text.split("\n")) buf.line(line, "diff-meta", 0, null);
        preview.replaceText(buf.text());
        var spans = buf.build();
        if (spans != null) preview.setStyleSpans(0, spans);
    }

    private void saveAs() {
        TreeEntry e = selected;
        if (e == null) return;
        FileChooser fc = new FileChooser();
        fc.setTitle("Save file as");
        fc.setInitialFileName(e.name());
        File target = fc.showSaveDialog(getScene() == null ? null : getScene().getWindow());
        if (target == null) return;
        repo.session().async(() -> {
            repo.session().git().saveFile(commit.hash(), e.path(), target.toPath());
            return null;
        }).whenComplete((v, err) -> { if (err != null) repo.reportError(err); });
    }

}
