package com.github.gitguiorg.gitgui.ui.view;

import com.github.gitguiorg.gitgui.model.Commit;
import com.github.gitguiorg.gitgui.model.FileDiff;
import com.github.gitguiorg.gitgui.ui.viewmodel.CompareViewModel;
import com.github.gitguiorg.gitgui.util.Texts;
import java.io.File;
import java.util.Objects;
import javafx.beans.binding.Bindings;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;

/** Changed files of a comparison, with a filter, and the commits that only the right side has. */
public final class CompareView extends BorderPane {
    public CompareView(CompareViewModel vm) {
        Label title = new Label(vm.label());
        title.setStyle("-fx-font-weight: bold;");
        Label counts = new Label();
        counts.textProperty().bind(Bindings.createStringBinding(
                () -> Texts.count(vm.files().size(), "file", "files") + " · "
                        + Texts.count(vm.commits().size(), "commit", "commits"),
                vm.files(), vm.commits()));
        counts.setOpacity(0.7);
        TextField filter = new TextField();
        filter.setPromptText("Filter files…");
        filter.setPrefColumnCount(18);
        filter.textProperty().bindBidirectional(vm.filterProperty());
        CheckBox mergeBase = new CheckBox("Since merge base");
        mergeBase.setTooltip(new Tooltip("Compare from the point where the two revisions diverged (a...b)"));
        mergeBase.selectedProperty().bindBidirectional(vm.mergeBaseProperty());
        Button export = new Button("Export patches…");
        export.setTooltip(new Tooltip("Write one .patch file per commit of the comparison into a folder"));
        export.setOnAction(e -> exportPatches(vm));
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(6, title, counts, spacer, filter, mergeBase, export);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(3, 6, 3, 6));
        setTop(bar);

        ListView<FileDiff> list = new ListView<>(vm.files());
        list.setPlaceholder(new Label("No differences"));
        list.setCellFactory(lv -> new FileCell());
        list.getSelectionModel().selectedItemProperty().addListener((o, a, d) -> {
            if (d != null) vm.select(d);
        });
        vm.files().addListener((ListChangeListener<FileDiff>) c -> {
            if (vm.files().isEmpty()) return;
            int index = -1;
            for (int i = 0; i < vm.files().size(); i++) {
                if (Objects.equals(vm.files().get(i).path(), vm.selectedPath())) index = i;
            }
            list.getSelectionModel().select(index < 0 ? 0 : index);
        });
        Label filesHeader = new Label("Changed files");
        filesHeader.setStyle("-fx-font-weight: bold;");
        filesHeader.setPadding(new Insets(2, 6, 2, 6));

        ListView<Commit> commits = new ListView<>(vm.commits());
        commits.setPlaceholder(new Label("No commits only on the right side"));
        commits.setFixedCellSize(24);
        commits.setCellFactory(lv -> new CommitRowCell());
        Label commitsHeader = new Label("Commits only on the right side");
        commitsHeader.setStyle("-fx-font-weight: bold;");
        commitsHeader.setPadding(new Insets(2, 6, 2, 6));

        SplitPane split = new SplitPane(section(filesHeader, list), section(commitsHeader, commits));
        split.setOrientation(Orientation.VERTICAL);
        split.setDividerPositions(0.62);
        setCenter(split);

        vm.load();
    }

    private void exportPatches(CompareViewModel vm) {
        DirectoryChooser dc = new DirectoryChooser();
        dc.setTitle("Folder for the patches");
        File dir = dc.showDialog(getScene() == null ? null : getScene().getWindow());
        if (dir != null) vm.exportPatches(dir.toPath());
    }

    private static VBox section(Label header, javafx.scene.Node content) {
        VBox box = new VBox(header, content);
        VBox.setVgrow(content, Priority.ALWAYS);
        return box;
    }

    private static final class FileCell extends ListCell<FileDiff> {
        @Override
        protected void updateItem(FileDiff d, boolean empty) {
            super.updateItem(d, empty);
            if (empty || d == null) {
                setText(null);
                setStyle("");
                return;
            }
            char mark = d.newFile() ? 'A' : d.deletedFile() ? 'D' : d.binary() ? 'B' : 'M';
            String name = d.path();
            if (d.oldPath() != null && d.newPath() != null && !d.oldPath().equals(d.newPath())) {
                name = d.oldPath() + " → " + name;
            }
            setText(mark + "   " + name);
            setStyle("-fx-text-fill: " + switch (mark) {
                case 'A' -> "#3fb950";
                case 'D' -> "#f85149";
                case 'B' -> "#6e7681";
                default -> "#d29922";
            } + ";");
        }
    }

    private static final class CommitRowCell extends ListCell<Commit> {
        @Override
        protected void updateItem(Commit c, boolean empty) {
            super.updateItem(c, empty);
            if (empty || c == null) {
                setGraphic(null);
                return;
            }
            Label hash = new Label(c.shortHash());
            hash.setMinWidth(60);
            hash.setStyle("-fx-font-family: 'Consolas', monospace; -fx-text-fill: #79c0ff;");
            Label subject = new Label(c.subject());
            subject.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(subject, Priority.ALWAYS);
            Label author = new Label(c.authorName());
            author.setMinWidth(150);
            author.setOpacity(0.75);
            HBox row = new HBox(6, hash, subject, author);
            row.setAlignment(Pos.CENTER_LEFT);
            setGraphic(row);
        }
    }
}
