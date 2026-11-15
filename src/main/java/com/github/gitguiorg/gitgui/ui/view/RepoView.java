package com.github.gitguiorg.gitgui.ui.view;

import com.github.gitguiorg.gitgui.ui.viewmodel.GitButlerViewModel;
import com.github.gitguiorg.gitgui.ui.viewmodel.RepoViewModel;
import java.util.List;
import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/** Content of one repository tab: toolbar, then sidebar | ((History | Changes) beside (commit details over diff)). */
public final class RepoView extends BorderPane {
    private final SplitPane body;
    private final SplitPane main;
    private final SplitPane details;
    private TabPane tabs;
    private LogPane logPane;
    private Tab historyTab;
    private Tab changesTab;
    private ChangesView changesView;
    private FileChangesPanel changesPanel;
    private GitButlerView gitButlerView;
    private double lastMainDivider = -1;
    private double lastDetailsDivider = -1;

    public RepoView(RepoViewModel vm) {
        SidebarTabs sidebar = new SidebarTabs(vm);
        sidebar.setMinWidth(0);

        logPane = new LogPane(vm);
        historyTab = new Tab("History", logPane);
        changesView = new ChangesView(vm.changes(), vm);
        changesTab = new Tab("Changes", changesView);
        historyTab.setClosable(false);
        changesTab.setClosable(false);
        changesTab.textProperty().bind(Bindings.createStringBinding(() -> {
            int n = vm.changes().unstaged().size() + vm.changes().staged().size();
            return n == 0 ? "Changes" : "Changes (" + n + ")";
        }, vm.changes().unstaged(), vm.changes().staged()));
        com.github.gitguiorg.gitgui.ui.viewmodel.ReflogViewModel reflogModel =
                new com.github.gitguiorg.gitgui.ui.viewmodel.ReflogViewModel(vm);
        Tab reflogTab = new Tab("Reflog", new ReflogView(reflogModel, vm));
        reflogTab.setClosable(false);
        tabs = new TabPane(historyTab, changesTab, reflogTab);
        java.util.Map<Tab, com.github.gitguiorg.gitgui.ui.viewmodel.CompareViewModel> compareTabs = new java.util.HashMap<>();
        Tab[] gitButlerTab = new Tab[1];

        DiffView diff = new DiffView(vm.diff(), vm.changes());
        changesPanel = new FileChangesPanel(vm.changes());
        details = new SplitPane(new CommitDetailView(vm.log()), diff);
        details.setOrientation(Orientation.VERTICAL);
        details.setDividerPositions(vm.settings().detailsDivider);
        main = new SplitPane(tabs, changesPanel, details);
        main.setDividerPositions(vm.settings().mainDivider, vm.settings().changesDivider);

        // The details pane (commit info + diff) stays on the right and follows the selection; Ctrl+D collapses it.
        lastMainDivider = vm.settings().mainDivider;
        lastDetailsDivider = vm.settings().changesDivider;
        Boolean[] override = {null};
        Runnable updateDetails = () -> {
            boolean want = override[0] == null || override[0];
            boolean shown = main.getItems().contains(details);
            if (want && !shown) {
                main.getItems().add(details);
                main.setDividerPositions(lastMainDivider, lastDetailsDivider);
            } else if (!want && shown) {
                double[] positions = main.getDividerPositions();
                if (positions.length > 0) lastMainDivider = positions[0];
                if (positions.length > 1) lastDetailsDivider = positions[1];
                main.getItems().remove(details);
            }
        };
        detailsToggle = () -> {
            override[0] = !main.getItems().contains(details);
            updateDetails.run();
        };
        tabs.getSelectionModel().selectedItemProperty().addListener((o, a, tab) -> {
            override[0] = null;
            updateDetails.run();
        });
        updateDetails.run();
        // The diff pane follows whichever tab is active.
        vm.log().selectedProperty().addListener((o, a, c) -> {
            Tab sel = tabs.getSelectionModel().getSelectedItem();
            if (sel == historyTab || sel == gitButlerTab[0]) vm.diff().showCommit(c);
        });
        tabs.getSelectionModel().selectedItemProperty().addListener((o, a, tab) -> {
            if (tab == gitButlerTab[0]) {
                vm.gitButler().activate();
                vm.changes().setActive(false);
                vm.diff().clear();
            } else if (!vm.isButWorkspace()) {
                vm.gitButler().deactivate();
            }
            if (tab == reflogTab) {
                vm.changes().setActive(false);
                reflogModel.load();
                return;
            }
            com.github.gitguiorg.gitgui.ui.viewmodel.CompareViewModel compare = tab == null ? null : compareTabs.get(tab);
            if (compare != null) {
                vm.changes().setActive(false);
                compare.reshow();
                return;
            }
            if (tab != historyTab && tab != changesTab) return;
            boolean changesActive = tab == changesTab;
            vm.changes().setActive(changesActive);
            if (changesActive) {
                vm.diff().clear();
                vm.changes().reshowSelection();
            } else {
                vm.diff().showCommit(vm.log().selectedProperty().get());
            }
        });

        // In a GitButler workspace the GitButler tab is the primary view and is opened automatically; otherwise
        // it is only opened on request (File menu) when the user enabled it.
        boolean[] butAuto = {false};
        Runnable openButler = () -> {
            if (butAuto[0]) {
                if (gitButlerView != null && main.getItems().contains(gitButlerView)) {
                    return;
                }
                com.github.gitguiorg.gitgui.ui.viewmodel.GitButlerViewModel model = vm.gitButler();
                GitButlerView view = new GitButlerView(model);
                gitButlerView = view;
                int idx = main.getItems().indexOf(tabs);
                if (idx >= 0) {
                    main.getItems().set(idx, view);
                } else {
                    main.getItems().add(0, view);
                }
                model.activate();
                return;
            }
            Tab existing = gitButlerTab[0];
            if (existing != null && tabs.getTabs().contains(existing)) {
                tabs.getSelectionModel().select(existing);
                return;
            }
            com.github.gitguiorg.gitgui.ui.viewmodel.GitButlerViewModel model = vm.gitButler();
            Tab t = new Tab("GitButler", new GitButlerView(model));
            t.setTooltip(new javafx.scene.control.Tooltip("GitButler workspace status from but.exe"));
            t.setClosable(!butAuto[0]);
            t.setOnClosed(e -> {
                model.deactivate();
                gitButlerTab[0] = null;
            });
            gitButlerTab[0] = t;
            if (butAuto[0]) tabs.getTabs().add(0, t);
            else tabs.getTabs().add(t);
            tabs.getSelectionModel().select(t);
        };
        vm.setGitButlerOpener(openButler);
        Runnable syncButler = () -> {
            boolean workspace = vm.isButWorkspace();
            if (workspace && gitButlerView == null && butAuto[0]) {
                openButler.run();
                vm.gitButler().activate();
            } else if (!workspace && gitButlerView != null && butAuto[0]) {
                int idx = main.getItems().indexOf(gitButlerView);
                if (idx >= 0) {
                    main.getItems().set(idx, tabs);
                }
                vm.gitButler().deactivate();
                gitButlerView = null;
                butAuto[0] = false;
            }
            if (workspace) vm.gitButler().activate();
        };
        vm.butWorkspaceProperty().addListener((o, a, b) -> syncButler.run());
        vm.log().setStackOf(h -> vm.isButWorkspace() ? vm.gitButler().stackOf(h) : 0);
        vm.gitButler().stackTickProperty().addListener((o, a, b) -> vm.log().stackTickProperty().set(vm.log().stackTickProperty().get() + 1));
        javafx.application.Platform.runLater(syncButler);

        java.util.Map<String, Tab> resolverTabs = new java.util.HashMap<>();
        vm.setResolverOpener(entry -> {
            Tab existing = resolverTabs.get(entry.path());
            if (existing != null) { tabs.getSelectionModel().select(existing); return; }
            Tab[] holder = new Tab[1];
            ResolverView view = new ResolverView(new com.github.gitguiorg.gitgui.ui.viewmodel.ResolverViewModel(vm, entry), () -> {
                tabs.getTabs().remove(holder[0]);
                resolverTabs.remove(entry.path());
            });
            String name = fileName(entry.path());
            Tab t = new Tab("! " + name, view);
            t.setTooltip(new javafx.scene.control.Tooltip("Resolve conflicts: " + entry.path()));
            t.setOnClosed(e -> resolverTabs.remove(entry.path()));
            holder[0] = t;
            resolverTabs.put(entry.path(), t);
            tabs.getTabs().add(t);
            tabs.getSelectionModel().select(t);
        });

        java.util.Map<String, Tab> extraTabs = new java.util.HashMap<>();
        vm.setBlameOpener((path, rev) -> {
            String key = "blame|" + path + "|" + rev;
            Tab existing = extraTabs.get(key);
            if (existing != null) { tabs.getSelectionModel().select(existing); return; }
            String name = fileName(path);
            Tab t = new Tab("Blame: " + name + (rev == null ? "" : " @" + com.github.gitguiorg.gitgui.util.Hashes.shortHash(rev)),
                    new BlameView(new com.github.gitguiorg.gitgui.ui.viewmodel.BlameViewModel(vm, path, rev), vm));
            t.setTooltip(new javafx.scene.control.Tooltip(path));
            t.setOnClosed(e -> extraTabs.remove(key));
            extraTabs.put(key, t);
            tabs.getTabs().add(t);
            tabs.getSelectionModel().select(t);
        });
        vm.setCommitFilesOpener(c -> {
            String key = "files|" + c.hash();
            Tab existing = extraTabs.get(key);
            if (existing != null) { tabs.getSelectionModel().select(existing); return; }
            Tab t = new Tab("Files: " + c.shortHash(), new CommitFilesView(c, vm));
            t.setTooltip(new javafx.scene.control.Tooltip("Files at " + c.hash()));
            t.setOnClosed(e -> extraTabs.remove(key));
            extraTabs.put(key, t);
            tabs.getTabs().add(t);
            tabs.getSelectionModel().select(t);
        });
        vm.setFileHistoryOpener(path -> {
            String key = "history|" + path;
            Tab existing = extraTabs.get(key);
            if (existing != null) { tabs.getSelectionModel().select(existing); return; }
            String name = fileName(path);
            Tab t = new Tab("History: " + name,
                    new FileHistoryView(new com.github.gitguiorg.gitgui.ui.viewmodel.FileHistoryViewModel(vm, path), vm));
            t.setTooltip(new javafx.scene.control.Tooltip(path));
            t.setOnClosed(e -> extraTabs.remove(key));
            extraTabs.put(key, t);
            tabs.getTabs().add(t);
            tabs.getSelectionModel().select(t);
        });
        vm.setCompareOpener((a, b) -> {
            String key = "compare|" + a + "|" + b;
            Tab existing = extraTabs.get(key);
            if (existing != null) { tabs.getSelectionModel().select(existing); return; }
            com.github.gitguiorg.gitgui.ui.viewmodel.CompareViewModel model =
                    new com.github.gitguiorg.gitgui.ui.viewmodel.CompareViewModel(vm, a, b);
            Tab t = new Tab("Compare " + model.label(), new CompareView(model));
            t.setTooltip(new javafx.scene.control.Tooltip("Changes and commits between " + a + " and " + (b == null ? "the working tree" : b)));
            t.setOnClosed(e -> { extraTabs.remove(key); compareTabs.remove(t); });
            extraTabs.put(key, t);
            compareTabs.put(t, model);
            tabs.getTabs().add(t);
            tabs.getSelectionModel().select(t);
        });

        body = new SplitPane(sidebar, main);
        body.setDividerPositions(vm.settings().sidebarDivider);
        addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            javafx.scene.input.KeyCode k = e.getCode();
            if (k == javafx.scene.input.KeyCode.F5) {
                vm.refresh();
                vm.log().reload();
                e.consume();
            } else if (e.isShortcutDown() && k == javafx.scene.input.KeyCode.F) {
                if (gitButlerTab[0] != null && tabs.getSelectionModel().getSelectedItem() == gitButlerTab[0]
                        && gitButlerTab[0].getContent() instanceof GitButlerView view) {
                    view.focusBranchFilter();
                } else {
                    tabs.getSelectionModel().select(historyTab);
                    logPane.focusSearch();
                }
                e.consume();
            } else if (e.isShortcutDown() && k == javafx.scene.input.KeyCode.D) {
                detailsToggle.run();
                e.consume();
            } else if (e.isShortcutDown() && k == javafx.scene.input.KeyCode.B) {
                toggleSidebar(sidebar);
                e.consume();
            } else if (e.isShortcutDown() && k == javafx.scene.input.KeyCode.DIGIT1) {
                tabs.getSelectionModel().select(historyTab);
                e.consume();
            } else if (e.isShortcutDown() && k == javafx.scene.input.KeyCode.DIGIT2) {
                tabs.getSelectionModel().select(changesTab);
                e.consume();
            } else if (!e.isShortcutDown() && !e.isAltDown() && !e.isControlDown() && k == KeyCode.C
                    && !(e.getTarget() instanceof TextInputControl)) {
                // GitButler-style: C jumps to the commit composer, unless the user is typing somewhere.
                if (composerFocusable()) {
                    focusComposer();
                    e.consume();
                }
            }
        });
        setTop(new NetworkToolbar(vm));
        setCenter(body);
        Runnable bottomSync = () -> {
            if (vm.isButWorkspace()) {
                setBottom(commitComposer(vm));
                composerShown = true;
            } else {
                setBottom(undoBanner(vm));
                composerShown = false;
                composerFocus = () -> { };
            }
        };
        bottomSync.run();
        vm.butWorkspaceProperty().addListener((o, a, b) -> bottomSync.run());
    }

    private double sidebarBefore = -1;
    private Runnable detailsToggle = () -> { };
    private Runnable composerFocus = () -> { };
    private boolean composerShown;

    /** Collapses the sidebar to give its width to the content, or restores it. */
    private void toggleSidebar(SidebarTabs sidebar) {
        if (body.getItems().contains(sidebar)) {
            sidebarBefore = body.getDividerPositions()[0];
            body.getItems().remove(sidebar);
        } else {
            body.getItems().add(0, sidebar);
            body.setDividerPositions(sidebarBefore > 0 ? sidebarBefore : 0.2);
        }
    }

    /** Copies the current divider positions into {@code s}, so the next run can restore them. */
    public void saveLayout(com.github.gitguiorg.gitgui.service.Settings s) {
        if (body.getDividerPositions().length > 0) s.sidebarDivider = body.getDividerPositions()[0];
        double[] positions = main.getDividerPositions();
        if (main.getItems().contains(details)) {
            if (positions.length > 0) lastMainDivider = positions[0];
            if (positions.length > 1) lastDetailsDivider = positions[1];
        } else if (positions.length > 0) {
            lastMainDivider = positions[0];
        }
        if (lastMainDivider > 0) s.mainDivider = lastMainDivider;
        if (lastDetailsDivider > 0) s.changesDivider = lastDetailsDivider;
        if (details.getDividerPositions().length > 0) s.detailsDivider = details.getDividerPositions()[0];
    }

    /** Shows the history tab and focuses its commit search box. */
    public void focusSearch() {
        tabs.getSelectionModel().select(historyTab);
        logPane.focusSearch();
    }

    /** Shows the changes tab and focuses the commit message field. */
    public void focusCommit() {
        tabs.getSelectionModel().select(changesTab);
        javafx.application.Platform.runLater(() -> changesView.focusCommitMessage());
    }

    /** Selects the history tab. */
    public void selectHistoryTab() {
        tabs.getSelectionModel().select(historyTab);
    }

    /** Selects the changes tab. */
    public void selectChangesTab() {
        tabs.getSelectionModel().select(changesTab);
    }

    /** Shows or hides the details (commit info + diff) pane. */
    public void toggleDetails() {
        detailsToggle.run();
    }

    /** Banner offering to reverse the last destructive operation; hidden when there is none. */
    private static javafx.scene.layout.HBox undoBanner(RepoViewModel vm) {
        Label label = new Label();
        Button undo = new Button("Undo");
        Button dismiss = new Button("✕");
        javafx.scene.layout.HBox banner = new javafx.scene.layout.HBox(8, label, undo, dismiss);
        banner.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        banner.setPadding(new javafx.geometry.Insets(2, 8, 2, 8));
        banner.setStyle("-fx-background-color: #2d333b;");
        banner.visibleProperty().bind(vm.undoOfferProperty().isNotNull());
        banner.managedProperty().bind(banner.visibleProperty());
        vm.undoOfferProperty().addListener((o, a, offer) -> {
            if (offer != null) label.setText(offer.message());
        });
        undo.setOnAction(e -> {
            RepoViewModel.UndoOffer offer = vm.undoOfferProperty().get();
            if (offer != null && Dialogs.confirm("Undo", offer.message() + "\n\nUndo it?")) vm.undo();
        });
        dismiss.setOnAction(e -> vm.dismissUndo());
        return banner;
    }

    /**
     * Inline commit composer of GitButler workspaces: target branch, scope (all files or the
     * selection made elsewhere in the view, including by dragging a file onto a branch), message and Ctrl+Enter.
     */
    private VBox commitComposer(RepoViewModel vm) {
        GitButlerViewModel butler = vm.gitButler();
        List<String> branches = butler.appliedBranchNames();
        ComboBox<String> branchBox = new ComboBox<>(FXCollections.observableArrayList(branches));
        if (!branches.isEmpty()) branchBox.setValue(branches.get(0));
        branchBox.setDisable(branches.isEmpty());
        branchBox.setTooltip(new Tooltip("Applied branch that receives the commit"));

        TextArea message = new TextArea();
        message.setPromptText("Commit message  (Ctrl+Enter)");
        message.setWrapText(true);
        message.setPrefRowCount(2);

        Label scope = new Label();
        scope.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-padding: 1 7 1 7;"
                + " -fx-background-radius: 9; -fx-background-color: rgba(139,92,246,0.18); -fx-text-fill: #8b5cf6;");
        Button clearScope = new Button("✕");
        clearScope.setTooltip(new Tooltip("Include every uncommitted change again"));
        clearScope.setStyle("-fx-background-color: transparent; -fx-padding: 0 4 0 4;");
        Label noBranch = new Label("Apply a branch to commit");
        noBranch.setStyle("-fx-font-size: 11px; -fx-text-fill: #d29922;");

        Button commit = new Button("Commit");
        commit.setStyle("-fx-font-weight: bold;");

        Runnable renderScope = () -> {
            List<String> paths = vm.composerPathsProperty().get();
            boolean scoped = !paths.isEmpty();
            scope.setText(!scoped ? "all changes"
                    : paths.size() == 1 ? paths.get(0) : paths.size() + " selected files");
            scope.setTooltip(new Tooltip(scoped && paths.size() != 1
                    ? String.join("\n", paths) : "Commit every uncommitted change"));
            clearScope.setVisible(scoped);
            clearScope.setManaged(scoped);
        };
        vm.composerPathsProperty().addListener((o, a, b) -> renderScope.run());
        renderScope.run();

        Runnable applyBranchHint = () -> {
            String want = vm.composerBranchProperty().get();
            if (want != null && !want.isBlank() && branchBox.getItems().contains(want)) branchBox.setValue(want);
        };
        vm.composerBranchProperty().addListener((o, a, b) -> applyBranchHint.run());

        Runnable doCommit = () -> {
            if (commit.isDisabled()) return;
            List<String> changes = allChangePaths(butler);
            // A selection can go stale (a file committed elsewhere): commit only what is still uncommitted.
            List<String> paths = vm.composerPathsProperty().get().isEmpty() ? changes
                    : vm.composerPathsProperty().get().stream().filter(changes::contains).toList();
            String text = message.getText() == null ? "" : message.getText().strip();
            if (paths.isEmpty()) {
                // Nothing of the selection is uncommitted any more: go back to the whole change set.
                if (!vm.composerPathsProperty().get().isEmpty()) vm.clearComposerScope();
                return;
            }
            if (text.isEmpty() || branchBox.getValue() == null) return;
            // The message survives a failed commit: it is cleared only once but reports success.
            butler.commitChanges(branchBox.getValue(), text, paths, () -> {
                message.clear();
                vm.clearComposerScope();
            });
        };
        message.setOnKeyPressed(e -> {
            if (e.isShortcutDown() && e.getCode() == KeyCode.ENTER) {
                doCommit.run();
                e.consume();
            }
        });
        commit.setOnAction(e -> doCommit.run());
        clearScope.setOnAction(e -> {
            vm.clearComposerScope();
            message.requestFocus();
        });
        commit.disableProperty().bind(Bindings.createBooleanBinding(
                () -> message.getText() == null || message.getText().isBlank()
                        || branchBox.getValue() == null || butler.actionBusyProperty().get()
                        || (vm.composerPathsProperty().get().isEmpty() && allChangePaths(butler).isEmpty()),
                message.textProperty(), branchBox.valueProperty(), vm.composerPathsProperty(),
                butler.statusProperty(), butler.actionBusyProperty()));
        noBranch.visibleProperty().bind(
                Bindings.createBooleanBinding(() -> branchBox.getItems().isEmpty(), branchBox.getItems()));
        noBranch.managedProperty().bind(noBranch.visibleProperty());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox top = new HBox(8, new Label("Branch:"), branchBox, scope, clearScope, noBranch, spacer, commit);
        top.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(4, top, message);
        box.setPadding(new Insets(4, 8, 4, 8));
        box.setStyle("-fx-background-color: #161b22; -fx-border-color: rgba(99,102,241,0.20); -fx-border-width: 1 0 0 0;");

        composerFocus = () -> {
            applyBranchHint.run();
            message.requestFocus();
            message.positionCaret(message.getText() == null ? 0 : message.getText().length());
        };
        vm.setComposerFocusHandler(() -> javafx.application.Platform.runLater(composerFocus));

        butler.stackTickProperty().addListener((o, a, b) -> {
            List<String> current = butler.appliedBranchNames();
            branchBox.getItems().setAll(current);
            if (!current.isEmpty() && (branchBox.getValue() == null || !current.contains(branchBox.getValue()))) {
                branchBox.setValue(current.get(0));
            }
            branchBox.setDisable(current.isEmpty());
            applyBranchHint.run();
        });
        return box;
    }

    /** Every uncommitted change but knows about, in status order; empty when there is nothing to commit. */
    private static List<String> allChangePaths(GitButlerViewModel butler) {
        com.github.gitguiorg.gitgui.git.ButStatus status = butler.statusProperty().get();
        if (status == null) return List.of();
        return status.uncommittedChanges().stream()
                .map(com.github.gitguiorg.gitgui.git.ButStatus.Change::path)
                .filter(p -> p != null && !p.isBlank())
                .distinct()
                .toList();
    }

    /** True when the commit composer is on screen, i.e. in a GitButler workspace. */
    private boolean composerFocusable() {
        return composerShown;
    }

    /** Puts the caret in the commit composer, for the C shortcut and the command palette. */
    public void focusComposer() {
        composerFocus.run();
    }

    /** Last segment of a repository-relative path; the path itself when it has no file name. */
    private static String fileName(String path) {
        java.nio.file.Path name = java.nio.file.Path.of(path).getFileName();
        return name == null ? path : name.toString();
    }
}
