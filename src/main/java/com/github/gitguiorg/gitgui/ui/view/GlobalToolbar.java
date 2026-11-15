package com.github.gitguiorg.gitgui.ui.view;

import com.github.gitguiorg.gitgui.model.RepoStatus;
import com.github.gitguiorg.gitgui.model.StatusEntry;
import com.github.gitguiorg.gitgui.ui.viewmodel.MainViewModel;
import com.github.gitguiorg.gitgui.ui.viewmodel.RepoViewModel;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import javafx.beans.InvalidationListener;
import javafx.geometry.Bounds;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuBar;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.Popup;
import org.kordamp.ikonli.javafx.FontIcon;

/** Global top toolbar: menu, quick actions, search over commands/branches/commits/files and repository status. */
final class GlobalToolbar extends HBox {
    private final MainViewModel vm;
    private final TextField search = new TextField();
    private final ListView<CommandPalette.Command> results = new ListView<>();
    private final Popup popup = new Popup();
    private final Label status = new Label("No repository");
    private final Label sync = new Label();
    private final Supplier<List<CommandPalette.Command>> commands;
    private final InvalidationListener statusListener = o -> refreshStatus();
    private RepoViewModel bound;

    GlobalToolbar(MainViewModel vm, MenuBar menu, Runnable onOpen, Runnable onClone, Runnable onSettings,
                  Supplier<List<CommandPalette.Command>> commands) {
        this.vm = vm;
        this.commands = commands;
        getStyleClass().add("global-toolbar");
        setAlignment(Pos.CENTER_LEFT);
        setSpacing(6);
        setPadding(new javafx.geometry.Insets(3, 8, 3, 8));

        Button open = new Button("Open", new FontIcon("mdi2f-folder-open-outline"));
        open.setOnAction(e -> onOpen.run());
        Button clone = new Button("Clone", new FontIcon("mdi2c-content-copy"));
        clone.setOnAction(e -> onClone.run());
        Button settings = new Button(null, new FontIcon("mdi2c-cog-outline"));
        settings.setTooltip(new javafx.scene.control.Tooltip("Settings"));
        settings.setOnAction(e -> onSettings.run());

        search.setPromptText("Search commands, branches, commits, files…");
        search.getStyleClass().add("global-search");
        search.setMaxWidth(460);
        HBox.setHgrow(search, Priority.ALWAYS);
        search.textProperty().addListener((o, a, text) -> updateResults(text));
        search.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                execute(results.getSelectionModel().getSelectedItem());
            } else if (e.getCode() == KeyCode.DOWN) {
                move(1);
            } else if (e.getCode() == KeyCode.UP) {
                move(-1);
            } else if (e.getCode() == KeyCode.ESCAPE) {
                popup.hide();
            } else {
                return;
            }
            e.consume();
        });
        search.focusedProperty().addListener((o, a, focused) -> {
            if (!focused) popup.hide();
        });

        results.getStyleClass().add("global-search-results");
        results.setFocusTraversable(false);
        results.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(CommandPalette.Command item, boolean empty) {
                super.updateItem(item, empty);
                if (item == null || empty) {
                    setText(null);
                    return;
                }
                setText(item.title() + (item.category().isBlank() ? "" : "   ·   " + item.category()));
            }
        });
        results.setOnMouseClicked(e -> execute(results.getSelectionModel().getSelectedItem()));
        popup.getContent().add(results);
        popup.setAutoHide(false);
        popup.setAutoFix(true);

        status.getStyleClass().add("global-toolbar-status");
        sync.getStyleClass().add("global-toolbar-sync");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        getChildren().addAll(menu, open, clone, search, spacer, sync, status, settings);

        vm.selectedProperty().addListener((o, a, repo) -> {
            if (bound != null) {
                bound.statusProperty().removeListener(statusListener);
                bound.network().busyProperty().removeListener(statusListener);
            }
            bound = repo;
            if (repo != null) {
                repo.statusProperty().addListener(statusListener);
                repo.network().busyProperty().addListener(statusListener);
            }
            refreshStatus();
        });
        refreshStatus();
    }

    /** Fills the popup with the commands matching the query; the popup follows the field. */
    private void updateResults(String query) {
        String q = query == null ? "" : query.strip();
        if (q.isEmpty()) {
            popup.hide();
            return;
        }
        List<CommandPalette.Command> matches = CommandPalette.filter(commands.get(), q, Map.of());
        results.getItems().setAll(matches.stream().limit(8).toList());
        if (results.getItems().isEmpty()) {
            popup.hide();
            return;
        }
        results.getSelectionModel().select(0);
        results.setPrefHeight(Math.min(240, 26 * results.getItems().size() + 4));
        if (!popup.isShowing()) {
            Bounds b = search.localToScreen(search.getBoundsInLocal());
            if (b == null) return;
            results.setPrefWidth(Math.max(280, b.getWidth()));
            if (search.getScene() != null) popup.show(search.getScene().getWindow(), b.getMinX(), b.getMaxY() + 2);
        }
    }

    private void move(int delta) {
        int size = results.getItems().size();
        if (size == 0) return;
        int next = Math.floorMod(results.getSelectionModel().getSelectedIndex() + delta, size);
        results.getSelectionModel().select(next);
        results.scrollTo(next);
    }

    private void execute(CommandPalette.Command command) {
        if (command == null) return;
        popup.hide();
        search.clear();
        command.action().run();
    }

    /** Repository name with branch, ahead/behind and change counts, plus a syncing marker. */
    private void refreshStatus() {
        RepoViewModel repo = bound;
        if (repo == null) {
            status.setText("No repository");
            sync.setText("");
            return;
        }
        StringBuilder sb = new StringBuilder(repo.name());
        if (repo.isButWorkspace()) sb.append("   ·   GitButler");
        RepoStatus s = repo.statusProperty().get();
        if (s != null) {
            if (s.ahead() > 0 || s.behind() > 0) sb.append("   ↑").append(s.ahead()).append(" ↓").append(s.behind());
            long dirty = s.entries().stream().filter(e -> e.kind() != StatusEntry.Kind.IGNORED).count();
            if (dirty > 0) sb.append("   ● ").append(dirty);
        }
        status.setText(sb.toString());
        sync.setText(repo.network().busyProperty().get() ? "Syncing…" : "");
    }
}