package com.github.gitguiorg.gitgui.ui.view;

import com.github.gitguiorg.gitgui.git.GitClient;
import com.github.gitguiorg.gitgui.model.GitRef;
import com.github.gitguiorg.gitgui.model.ReflogEntry;
import com.github.gitguiorg.gitgui.ui.viewmodel.ReflogViewModel;
import com.github.gitguiorg.gitgui.ui.viewmodel.RepoViewModel;
import java.util.ArrayList;
import java.util.List;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

/** Reflog of HEAD or of a branch, with recovery actions on the selected entry. */
public final class ReflogView extends BorderPane {
    public ReflogView(ReflogViewModel vm, RepoViewModel repo) {
        ComboBox<String> refs = new ComboBox<>();
        refs.setValue("HEAD");
        syncRefs(refs, repo);
        repo.refs().addListener((javafx.collections.ListChangeListener<GitRef>) c -> syncRefs(refs, repo));
        refs.valueProperty().addListener((o, a, b) -> { if (b != null) vm.refProperty().set(b); });

        Button refresh = new Button("Refresh");
        refresh.setOnAction(e -> vm.load());

        Label title = new Label("Reflog");
        title.setStyle("-fx-font-weight: bold;");
        HBox header = new HBox(6, title, refs, refresh);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(3, 6, 3, 6));

        ListView<ReflogEntry> list = new ListView<>(vm.entries());
        list.setFixedCellSize(24);
        list.setPlaceholder(new Label("No reflog entries"));
        list.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(ReflogEntry e, boolean empty) {
                super.updateItem(e, empty);
                if (empty || e == null) {
                    setGraphic(null);
                    return;
                }
                Label selector = new Label(e.selector());
                selector.setMinWidth(110);
                selector.setStyle("-fx-font-family: 'Consolas', monospace; -fx-text-fill: #79c0ff;");
                Label hash = new Label(e.shortHash());
                hash.setMinWidth(64);
                hash.setStyle("-fx-font-family: 'Consolas', monospace; -fx-text-fill: #79c0ff; -fx-opacity: 0.8;");
                Label action = new Label(e.action());
                action.setMinWidth(110);
                action.setStyle("-fx-font-weight: bold;");
                Label message = new Label(e.message());
                message.setMaxWidth(Double.MAX_VALUE);
                HBox.setHgrow(message, Priority.ALWAYS);
                Label date = new Label(e.date() == null ? "" : com.github.gitguiorg.gitgui.util.DateFormats.dateTime(e.date()));
                date.setMinWidth(120);
                date.setOpacity(0.75);
                HBox row = new HBox(6, selector, hash, action, message, date);
                row.setAlignment(Pos.CENTER_LEFT);
                setGraphic(row);
            }
        });
        list.getSelectionModel().selectedItemProperty().addListener((o, a, e) -> vm.select(e));
        list.setContextMenu(menu(repo, list));

        setTop(header);
        setCenter(list);
    }

    private static void syncRefs(ComboBox<String> refs, RepoViewModel repo) {
        String selected = refs.getValue();
        List<String> names = new ArrayList<>();
        names.add("HEAD");
        for (GitRef r : repo.refs()) if (r.type() == GitRef.Type.LOCAL_BRANCH) names.add(r.shortName());
        if (names.equals(refs.getItems())) return;
        refs.getItems().setAll(names);
        if (selected != null && names.contains(selected)) refs.setValue(selected);
    }

    private static ContextMenu menu(RepoViewModel repo, ListView<ReflogEntry> list) {
        MenuItem copy = item("Copy hash", () -> {
            ReflogEntry e = list.getSelectionModel().getSelectedItem();
            if (e == null) return;
            ClipboardContent cc = new ClipboardContent();
            cc.putString(e.hash());
            Clipboard.getSystemClipboard().setContent(cc);
        });
        MenuItem checkout = item("Checkout (detached)", () -> {
            ReflogEntry e = list.getSelectionModel().getSelectedItem();
            if (e != null) repo.checkoutCommit(e.hash());
        });
        MenuItem branch = item("New Branch…", () -> {
            ReflogEntry e = list.getSelectionModel().getSelectedItem();
            if (e == null) return;
            Dialogs.askBranch(e.shortHash()).ifPresent(r -> repo.createBranch(r.name(), e.hash(), r.checkout()));
        });
        MenuItem pick = item("Cherry-pick", () -> {
            ReflogEntry e = list.getSelectionModel().getSelectedItem();
            if (e != null) repo.cherryPick(e.hash());
        });
        MenuItem diff = item("Show diff", () -> vmSelect(repo, list));
        Menu reset = new Menu("Reset current branch to here", null,
                item("Soft", () -> reset(repo, list, GitClient.ResetMode.SOFT)),
                item("Mixed", () -> reset(repo, list, GitClient.ResetMode.MIXED)),
                item("Hard (discards changes)", () -> reset(repo, list, GitClient.ResetMode.HARD)));
        return new ContextMenu(copy, new SeparatorMenuItem(), diff, checkout, branch, pick,
                new SeparatorMenuItem(), reset);
    }

    private static void vmSelect(RepoViewModel repo, ListView<ReflogEntry> list) {
        ReflogEntry e = list.getSelectionModel().getSelectedItem();
        if (e != null) repo.diff().showCommitByHash(e.hash(), e.summary());
    }

    private static void reset(RepoViewModel repo, ListView<ReflogEntry> list, GitClient.ResetMode mode) {
        ReflogEntry e = list.getSelectionModel().getSelectedItem();
        if (e == null) return;
        String what = mode == GitClient.ResetMode.HARD
                ? "Hard reset to " + e.shortHash() + "? Uncommitted changes will be lost."
                : "Reset current branch to " + e.shortHash() + " ("
                        + mode.name().toLowerCase(java.util.Locale.ROOT) + ")?";
        if (Dialogs.confirm("Reset", what)) repo.reset(e.hash(), mode);
    }

    private static MenuItem item(String text, Runnable action) {
        MenuItem mi = new MenuItem(text);
        mi.setOnAction(e -> action.run());
        return mi;
    }
}
