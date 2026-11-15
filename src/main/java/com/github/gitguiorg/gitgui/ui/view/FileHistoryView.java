package com.github.gitguiorg.gitgui.ui.view;

import com.github.gitguiorg.gitgui.model.Commit;
import com.github.gitguiorg.gitgui.ui.viewmodel.FileHistoryViewModel;
import com.github.gitguiorg.gitgui.ui.viewmodel.RepoViewModel;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

/** Commits that touched one file; selecting one shows that file's diff in the shared diff pane. */
public final class FileHistoryView extends BorderPane {
    public FileHistoryView(FileHistoryViewModel vm, RepoViewModel repo) {
        ListView<Commit> list = new ListView<>(vm.commits());
        list.setFixedCellSize(24);
        list.setPlaceholder(new Label("No history"));
        list.setCellFactory(lv -> new ListCell<>() {
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
                Label date = new Label(com.github.gitguiorg.gitgui.util.DateFormats.dateTime(c.authorDate()));
                date.setMinWidth(120);
                date.setOpacity(0.75);
                HBox row = new HBox(8, hash, subject, author, date);
                row.setAlignment(Pos.CENTER_LEFT);
                setGraphic(row);
                if (getIndex() >= vm.commits().size() - 20) vm.loadMore();
            }
        });
        list.getSelectionModel().selectedItemProperty().addListener((o, a, c) -> { if (c != null) vm.select(c); });
        MenuItem blame = new MenuItem("Blame at this commit");
        blame.setOnAction(e -> {
            Commit c = list.getSelectionModel().getSelectedItem();
            if (c != null) repo.openBlame(vm.path(), c.hash());
        });
        list.setContextMenu(new ContextMenu(blame));

        Label title = new Label("History of " + vm.path());
        title.setStyle("-fx-font-weight: bold;");
        title.setPadding(new Insets(3, 6, 3, 6));
        setTop(title);
        setCenter(list);
        vm.loadMore();
    }
}
