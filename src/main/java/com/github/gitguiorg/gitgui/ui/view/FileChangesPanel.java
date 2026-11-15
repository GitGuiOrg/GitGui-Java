package com.github.gitguiorg.gitgui.ui.view;

import com.github.gitguiorg.gitgui.model.StatusEntry;
import com.github.gitguiorg.gitgui.ui.viewmodel.ChangesViewModel;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javafx.collections.ListChangeListener;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * Changed-files panel for the right-hand side of the repository view: staged and unstaged files with a
 * checkbox per file, quick stage/unstage and open-in-editor. Clicking a file previews its diff; hunk-level
 * staging stays in the diff view.
 */
final class FileChangesPanel extends VBox {
    /** One row: the status entry and whether it comes from the staged list. */
    private record Item(StatusEntry entry, boolean staged) {
        String key() { return (staged ? "S:" : "U:") + entry.path(); }
    }

    private final ChangesViewModel vm;
    private final ListView<Item> list = new ListView<>();
    private final Label count = new Label();
    private final Button stage = new Button("Stage");
    private final Button unstage = new Button("Unstage");
    private final Button edit = new Button("Edit");
    private final Set<String> checked = new HashSet<>();

    FileChangesPanel(ChangesViewModel vm) {
        this.vm = vm;
        getStyleClass().add("changes-panel");
        setSpacing(4);
        setPadding(new javafx.geometry.Insets(4, 6, 4, 6));

        Label title = new Label("Changes");
        title.setStyle("-fx-font-weight: bold;");
        count.getStyleClass().add("muted");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(6, title, count, spacer, stage, unstage, edit);
        header.setAlignment(Pos.CENTER_LEFT);
        stage.setOnAction(e -> apply(false));
        unstage.setOnAction(e -> apply(true));
        edit.setOnAction(e -> openSelectedInEditor());
        getChildren().addAll(header, list);
        VBox.setVgrow(list, Priority.ALWAYS);

        list.setPlaceholder(new Label("No changed files"));
        list.setCellFactory(v -> new Cell());
        list.getSelectionModel().selectedItemProperty().addListener((o, a, item) -> {
            if (item != null) {
                vm.select(item.entry(), item.staged());
                vm.reshowSelection();
            }
        });

        vm.staged().addListener((ListChangeListener<StatusEntry>) c -> rebuild());
        vm.unstaged().addListener((ListChangeListener<StatusEntry>) c -> rebuild());
        rebuild();
    }

    /** Rebuilds the combined staged/unstaged list, keeping the checkbox state of the files still present. */
    private void rebuild() {
        List<Item> items = new ArrayList<>();
        for (StatusEntry e : vm.staged()) items.add(new Item(e, true));
        for (StatusEntry e : vm.unstaged()) items.add(new Item(e, false));
        checked.removeIf(key -> items.stream().noneMatch(i -> i.key().equals(key)));
        list.getItems().setAll(items);
        int n = items.size();
        count.setText(n == 0 ? "No changes" : n + (n == 1 ? " file" : " files"));
        updateButtons();
    }

    /** Stages the checked unstaged files, or unstages the checked staged ones. */
    private void apply(boolean stagedSide) {
        List<StatusEntry> entries = checkedEntries(stagedSide);
        if (entries.isEmpty()) return;
        if (stagedSide) vm.unstage(entries); else vm.stage(entries);
        checked.clear();
        updateButtons();
    }

    private List<StatusEntry> checkedEntries(boolean stagedSide) {
        List<StatusEntry> out = new ArrayList<>();
        for (Item i : list.getItems()) {
            if (i.staged() == stagedSide && checked.contains(i.key())) out.add(i.entry());
        }
        return out;
    }

    private void openSelectedInEditor() {
        String path = firstCheckedPath();
        if (path == null) {
            Item item = list.getSelectionModel().getSelectedItem();
            path = item == null ? null : item.entry().path();
        }
        if (path != null) vm.openInEditor(path);
    }

    private String firstCheckedPath() {
        for (Item i : list.getItems()) {
            if (checked.contains(i.key())) return i.entry().path();
        }
        return null;
    }

    private void updateButtons() {
        stage.setDisable(checkedEntries(false).isEmpty());
        unstage.setDisable(checkedEntries(true).isEmpty());
        edit.setDisable(checked.isEmpty() && list.getSelectionModel().getSelectedItem() == null);
    }

    /** One file row: checkbox, path and a status pill. Double click stages or unstages the file. */
    private final class Cell extends ListCell<Item> {
        private final CheckBox check = new CheckBox();
        private final Label path = new Label();
        private final Label badge = new Label();
        private final HBox row = new HBox(6);

        Cell() {
            path.getStyleClass().add("changes-file-path");
            badge.getStyleClass().add("pill");
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getChildren().addAll(check, path, spacer, badge);
            check.setOnAction(e -> {
                Item item = getItem();
                if (item == null) return;
                if (check.isSelected()) checked.add(item.key()); else checked.remove(item.key());
                updateButtons();
            });
            row.setOnMouseClicked(e -> {
                Item item = getItem();
                if (item == null) return;
                if (e.getClickCount() == 2) {
                    if (item.staged()) vm.unstage(List.of(item.entry())); else vm.stage(List.of(item.entry()));
                } else {
                    list.getSelectionModel().select(item);
                }
            });
        }

        @Override
        protected void updateItem(Item item, boolean empty) {
            super.updateItem(item, empty);
            if (item == null || empty) {
                setText(null);
                setGraphic(null);
                return;
            }
            check.setSelected(checked.contains(item.key()));
            path.setText(item.entry().path());
            path.setTooltip(new Tooltip(item.entry().path()));
            String label;
            String style;
            if (item.entry().isConflicted()) {
                label = "conflict";
                style = "red";
            } else if (item.staged()) {
                label = "staged";
                style = "blue";
            } else if (item.entry().kind() == StatusEntry.Kind.UNTRACKED) {
                label = "new";
                style = "amber";
            } else {
                label = "modified";
                style = "gray";
            }
            badge.setText(label);
            badge.getStyleClass().removeAll("accent", "green", "red", "amber", "blue", "gray");
            badge.getStyleClass().add(style);
            setText(null);
            setGraphic(row);
        }
    }
}