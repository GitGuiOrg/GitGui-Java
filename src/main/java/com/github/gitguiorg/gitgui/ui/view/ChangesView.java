package com.github.gitguiorg.gitgui.ui.view;

import com.github.gitguiorg.gitgui.model.StatusEntry;
import com.github.gitguiorg.gitgui.ui.viewmodel.ChangesViewModel;
import java.util.List;
import javafx.beans.binding.Bindings;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/** Unstaged and staged file lists, with the commit form below them (the composer link in GitButler workspaces). */
public final class ChangesView extends BorderPane {
    private final ChangesViewModel vm;
    private final com.github.gitguiorg.gitgui.ui.viewmodel.RepoViewModel repo;
    private final ListView<StatusEntry> unstagedList = new ListView<>();
    private final ListView<StatusEntry> stagedList = new ListView<>();
    private final CommitForm commitForm;
    private final VBox composerNote;
    private final SplitPane split;
    private boolean restoring;

    public ChangesView(ChangesViewModel vm, com.github.gitguiorg.gitgui.ui.viewmodel.RepoViewModel repo) {
        this.vm = vm;
        this.repo = repo;
        unstagedList.setItems(vm.unstaged());
        stagedList.setItems(vm.staged());
        unstagedList.setCellFactory(lv -> new FileCell(false));
        stagedList.setCellFactory(lv -> new FileCell(true));
        unstagedList.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        stagedList.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        unstagedList.setPlaceholder(new EmptyState("mdi2g-git", "No unstaged changes",
                "The working tree is clean. Modify a file or checkout a branch to get started.",
                null, null));
        stagedList.setPlaceholder(new EmptyState("mdi2t-package-down", "Nothing staged",
                "Select files from the unstaged list and stage them before committing.",
                null, null));

        wire(unstagedList, stagedList, false);
        wire(stagedList, unstagedList, true);
        vm.unstaged().addListener((ListChangeListener<StatusEntry>) c -> restore(unstagedList, false));
        vm.staged().addListener((ListChangeListener<StatusEntry>) c -> restore(stagedList, true));

        Button stageSel = new Button("Stage");
        stageSel.setOnAction(e -> vm.stage(List.copyOf(unstagedList.getSelectionModel().getSelectedItems())));
        Button stageAll = new Button("Stage all");
        stageAll.setOnAction(e -> vm.stageAll());
        Button discardSel = new Button("Discard");
        discardSel.setOnAction(e -> discard(List.copyOf(unstagedList.getSelectionModel().getSelectedItems())));
        Button unstageSel = new Button("Unstage");
        unstageSel.setOnAction(e -> vm.unstage(List.copyOf(stagedList.getSelectionModel().getSelectedItems())));
        Button unstageAll = new Button("Unstage all");
        unstageAll.setOnAction(e -> vm.unstageAll());
        stageSel.disableProperty().bind(Bindings.isEmpty(unstagedList.getSelectionModel().getSelectedItems()));
        discardSel.disableProperty().bind(Bindings.isEmpty(unstagedList.getSelectionModel().getSelectedItems()));
        unstageSel.disableProperty().bind(Bindings.isEmpty(stagedList.getSelectionModel().getSelectedItems()));
        stageAll.disableProperty().bind(Bindings.isEmpty(vm.unstaged()));
        unstageAll.disableProperty().bind(Bindings.isEmpty(vm.staged()));
        CheckBox showIgnored = new CheckBox("Ignored");
        showIgnored.setTooltip(new Tooltip("Also list the files matched by .gitignore and the exclude files"));
        showIgnored.selectedProperty().bindBidirectional(vm.showIgnoredProperty());
        Button clean = new Button("Clean…");
        clean.setTooltip(new Tooltip("Delete untracked files after a preview"));
        clean.setOnAction(e -> cleanDialog());

        VBox top = section("Unstaged", vm.unstaged(), unstagedList, stageSel, stageAll, discardSel, clean, showIgnored);
        VBox bottom = section("Staged", vm.staged(), stagedList, unstageSel, unstageAll);
        SplitPane lists = new SplitPane(top, bottom);
        lists.setOrientation(Orientation.VERTICAL);

        commitForm = new CommitForm(vm);
        composerNote = composerNote();
        split = new SplitPane(lists, commitForm);
        split.setDividerPositions(0.6);
        repo.butWorkspaceProperty().addListener((o, a, b) -> showCommitPane());
        showCommitPane();
        setTop(new OperationBanner(vm));
        setCenter(split);
    }

    /** A GitButler workspace commits from the composer at the bottom, so the form is replaced by a link to it. */
    private void showCommitPane() {
        if (split.getItems().size() < 2) return;
        javafx.scene.Node want = repo.isButWorkspace() ? composerNote : commitForm;
        if (split.getItems().get(1) != want) split.getItems().set(1, want);
    }

    private VBox composerNote() {
        Label title = new Label("Commit from the composer");
        title.setStyle("-fx-font-weight: bold;");
        Label text = new Label("In a GitButler workspace commits are created in the composer below: "
                + "scegli il branch, il messaggio e premi Ctrl+Enter. Anche la scorciatoia C porta li'.\n"
                + "The files selected above are passed to the composer as the commit scope.");
        text.setWrapText(true);
        text.setStyle("-fx-text-fill: #8b949e; -fx-font-size: 11px;");
        Button commitStaged = new Button("Commit in composer");
        commitStaged.setTooltip(new Tooltip("Opens the composer with the selected files (or all staged files) as scope"));
        commitStaged.disableProperty().bind(Bindings.isEmpty(vm.staged()));
        commitStaged.setOnAction(e -> {
            List<StatusEntry> chosen = List.copyOf(stagedList.getSelectionModel().getSelectedItems());
            if (chosen.isEmpty()) chosen = List.copyOf(vm.staged());
            repo.composeCommit(chosen.stream().map(StatusEntry::path).toList(), null);
        });
        Button focus = new Button("Vai al composer");
        focus.setOnAction(e -> repo.focusComposer());
        HBox actions = new HBox(8, commitStaged, focus);
        actions.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        VBox box = new VBox(6, title, text, actions);
        box.setPadding(new Insets(8));
        box.setStyle("-fx-background-color: #161b22;");
        return box;
    }

    /** Puts the caret in the commit message field, e.g. after choosing Commit from the command palette. */
    void focusCommitMessage() {
        if (repo.isButWorkspace()) {
            repo.focusComposer();
            return;
        }
        commitForm.focusMessage();
    }

    private static VBox section(String title, javafx.collections.ObservableList<StatusEntry> items,
                                ListView<StatusEntry> list, javafx.scene.Node... extras) {
        Label header = new Label(title);
        header.setStyle("-fx-font-weight: bold;");
        header.textProperty().bind(Bindings.createStringBinding(() -> title + " ("
                + items.stream().filter(e -> e.kind() != StatusEntry.Kind.IGNORED).count() + ")", items));
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(6, header, spacer);
        bar.getChildren().addAll(extras);
        bar.setPadding(new Insets(2, 6, 2, 6));
        VBox box = new VBox(bar, list);
        VBox.setVgrow(list, Priority.ALWAYS);
        return box;
    }

    private void wire(ListView<StatusEntry> list, ListView<StatusEntry> other, boolean staged) {
        list.getSelectionModel().selectedItemProperty().addListener((o, a, e) -> {
            if (restoring || e == null) return;
            other.getSelectionModel().clearSelection();
            vm.select(e, staged);
        });
        list.setOnMouseClicked(ev -> {
            if (ev.getClickCount() == 2 && ev.getButton() == javafx.scene.input.MouseButton.PRIMARY) {
                List<StatusEntry> sel = List.copyOf(list.getSelectionModel().getSelectedItems());
                if (sel.isEmpty()) return;
                if (!staged && sel.get(0).isConflicted()) { vm.openResolver(sel.get(0)); return; }
                if (staged) vm.unstage(sel); else vm.stage(sel);
            }
        });
        MenuItem toggle = new MenuItem(staged ? "Unstage" : "Stage");
        toggle.setOnAction(e -> {
            List<StatusEntry> sel = List.copyOf(list.getSelectionModel().getSelectedItems());
            if (staged) vm.unstage(sel); else vm.stage(sel);
        });
        ContextMenu menu = new ContextMenu(toggle);
        MenuItem ignoreFile = new MenuItem("This file");
        MenuItem ignoreFolder = new MenuItem("This folder");
        MenuItem ignoreExt = new MenuItem("All files with this extension");
        ignoreFile.setOnAction(e -> ignore(list, "file"));
        ignoreFolder.setOnAction(e -> ignore(list, "folder"));
        ignoreExt.setOnAction(e -> ignore(list, "ext"));
        Menu ignore = new Menu("Ignore");
        ignore.getItems().addAll(ignoreFile, ignoreFolder, ignoreExt);
        MenuItem stopTracking = new MenuItem("Stop tracking (keep file)");
        stopTracking.setOnAction(e -> vm.stopTracking(List.copyOf(list.getSelectionModel().getSelectedItems())));
        if (!staged) {
            MenuItem discard = new MenuItem("Discard changes…");
            discard.setOnAction(e -> discard(List.copyOf(list.getSelectionModel().getSelectedItems())));
            menu.getItems().addAll(discard, new SeparatorMenuItem(), ignore, stopTracking);
        }
        MenuItem openEditor = new MenuItem("Open in editor");
        openEditor.setOnAction(e -> {
            StatusEntry s = list.getSelectionModel().getSelectedItem();
            if (s != null) vm.openInEditor(s.path());
        });
        MenuItem blame = new MenuItem("Blame");
        blame.setOnAction(e -> {
            StatusEntry s = list.getSelectionModel().getSelectedItem();
            if (s != null) vm.openBlame(s.path(), null);
        });
        MenuItem fileHistory = new MenuItem("File history");
        fileHistory.setOnAction(e -> {
            StatusEntry s = list.getSelectionModel().getSelectedItem();
            if (s != null) vm.openFileHistory(s.path());
        });
        menu.getItems().addAll(new SeparatorMenuItem(), openEditor, blame, fileHistory);
        menu.setOnShowing(e -> {
            StatusEntry s = list.getSelectionModel().getSelectedItem();
            boolean tracked = s != null && s.kind() != StatusEntry.Kind.UNTRACKED && s.kind() != StatusEntry.Kind.IGNORED;
            boolean deleted = s != null && (s.indexState() == 'D' || s.worktreeState() == 'D');
            openEditor.setDisable(s == null || deleted);
            blame.setDisable(!tracked || deleted);
            fileHistory.setDisable(!tracked);
            boolean untracked = s != null && s.kind() == StatusEntry.Kind.UNTRACKED;
            String ext = s == null ? null : extension(s.path());
            ignore.setDisable(!untracked);
            ignoreFolder.setDisable(s == null || !s.path().contains("/"));
            ignoreExt.setText(ext == null ? "All files with this extension" : "All " + ext + " files");
            ignoreExt.setDisable(ext == null);
            stopTracking.setDisable(!tracked || !vm.isIgnoredTracked(s.path()));
        });
        list.setContextMenu(menu);
    }

    /** Extension of the file name, dot included, or null when it has none. */
    private static String extension(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot) : null;
    }

    /** Adds an ignore rule for the selected path: the file, its folder or all files with its extension. */
    private void ignore(ListView<StatusEntry> list, String scope) {
        StatusEntry s = list.getSelectionModel().getSelectedItem();
        if (s == null) return;
        String path = s.path();
        String ext = extension(path);
        String pattern = switch (scope) {
            case "file" -> "/" + path;
            case "folder" -> path.contains("/") ? "/" + path.substring(0, path.lastIndexOf('/') + 1) : null;
            case "ext" -> ext == null ? null : "*" + ext;
            default -> null;
        };
        if (pattern == null) return;
        Dialogs.askIgnore("Add " + pattern + " to the ignore rules", pattern)
                .ifPresent(r -> vm.ignore(r.pattern(), r.localOnly()));
    }

    /** Preview of {@code git clean}, then deletion of the selected paths. */
    private void cleanDialog() {
        CheckBox includeIgnored = new CheckBox("Also delete ignored files (-x)");
        ListView<String> list = new ListView<>();
        list.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        list.setPrefSize(460, 280);
        list.setPlaceholder(new Label("Nothing to clean"));
        Button selectAll = new Button("Select all");
        selectAll.setOnAction(e -> list.getSelectionModel().selectAll());
        Button selectNone = new Button("Select none");
        selectNone.setOnAction(e -> list.getSelectionModel().clearSelection());
        VBox box = new VBox(8, new Label("These untracked files will be deleted permanently:"), list,
                new HBox(6, selectAll, selectNone), includeIgnored);
        box.setPadding(new Insets(8));
        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle("Clean untracked files");
        d.setHeaderText("Delete untracked files");
        d.getDialogPane().setContent(box);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        javafx.scene.Node ok = d.getDialogPane().lookupButton(ButtonType.OK);
        ok.setDisable(true);
        list.getSelectionModel().getSelectedItems().addListener(
                (ListChangeListener<String>) c -> ok.setDisable(list.getSelectionModel().getSelectedItems().isEmpty()));
        Runnable load = () -> vm.cleanPreview(includeIgnored.isSelected(), items -> {
            list.setItems(javafx.collections.FXCollections.observableArrayList(items));
            list.getSelectionModel().selectAll();
            ok.setDisable(items.isEmpty());
        });
        includeIgnored.selectedProperty().addListener((o, a, b) -> load.run());
        load.run();
        d.showAndWait().filter(b -> b == ButtonType.OK).ifPresent(b -> {
            List<String> paths = List.copyOf(list.getSelectionModel().getSelectedItems());
            if (!paths.isEmpty()) vm.clean(paths, includeIgnored.isSelected());
        });
    }

    /** After a refresh ListView drops its selection; re-select the remembered file or clear the diff. */
    private void restore(ListView<StatusEntry> list, boolean staged) {
        String path = vm.selectedPath();
        if (path == null || vm.selectedStaged() != staged) return;
        restoring = true;
        try {
            for (int i = 0; i < list.getItems().size(); i++) {
                if (list.getItems().get(i).path().equals(path)) {
                    list.getSelectionModel().select(i);
                    vm.select(list.getItems().get(i), staged);
                    return;
                }
            }
            vm.deselect();
        } finally {
            restoring = false;
        }
    }

    private void discard(List<StatusEntry> entries) {
        if (entries.isEmpty()) return;
        String what = entries.size() == 1 ? entries.get(0).path() : entries.size() + " files";
        Alert a = new Alert(Alert.AlertType.CONFIRMATION, "Discard changes to " + what + "? This cannot be undone.",
                ButtonType.OK, ButtonType.CANCEL);
        a.showAndWait().filter(b -> b == ButtonType.OK).ifPresent(b -> vm.discard(entries));
    }

    private static final class FileCell extends ListCell<StatusEntry> {
        private final boolean staged;

        FileCell(boolean staged) { this.staged = staged; }

        @Override
        protected void updateItem(StatusEntry e, boolean empty) {
            super.updateItem(e, empty);
            if (empty || e == null) {
                setText(null);
                setStyle("");
                return;
            }
            char c = e.isConflicted() ? 'U' : e.kind() == StatusEntry.Kind.UNTRACKED ? '?' : staged ? e.indexState() : e.worktreeState();
            String name = e.origPath() != null && staged ? e.origPath() + " → " + e.path() : e.path();
            setText(c + "   " + name);
            String color = switch (c) {
                case 'A', '?' -> "#3fb950";
                case '!' -> "#6e7681";
                case 'D' -> "#f85149";
                case 'R', 'C' -> "#58a6ff";
                case 'U' -> "#ff7b72";
                default -> "#d29922";
            };
            setStyle("-fx-text-fill: " + color + ";");
        }
    }
}
