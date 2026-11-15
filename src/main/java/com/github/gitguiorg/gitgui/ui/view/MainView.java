package com.github.gitguiorg.gitgui.ui.view;

import com.github.gitguiorg.gitgui.ui.Fx;
import com.github.gitguiorg.gitgui.ui.viewmodel.MainViewModel;
import com.github.gitguiorg.gitgui.ui.viewmodel.RepoViewModel;
import com.github.gitguiorg.gitgui.model.Commit;
import com.github.gitguiorg.gitgui.model.GitRef;
import com.github.gitguiorg.gitgui.model.RepoStatus;
import com.github.gitguiorg.gitgui.model.StatusEntry;
import com.github.gitguiorg.gitgui.util.AppInfo;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javafx.beans.binding.Bindings;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Window;

public final class MainView extends BorderPane {
    private final MainViewModel vm;
    private final TabPane tabs = new TabPane();
    private final WelcomeView welcome;
    private final Menu recentMenu = new Menu("Open Recent");
    private final CommandPalette palette;
    private int dragFrom = -1;
    private double pressX;
    private double pressY;

    public MainView(MainViewModel vm) {
        this.vm = vm;
        vm.setErrorHandler(e -> Fx.showError("Git error", e));

        welcome = new WelcomeView(vm, this::chooseAndOpen, this::cloneRepo, this::chooseAndInit);
        vm.setRecentChangedHandler(welcome::reload);
        MenuBar menuBar = buildMenu();
        menuBar.setStyle("-fx-background-color: transparent;");
        menuBar.setMaxSize(javafx.scene.layout.Region.USE_PREF_SIZE, 26);
        tabs.getStyleClass().add("repo-tabs");
        StackPane center = new StackPane(welcome, tabs);
        StackPane.setAlignment(welcome, Pos.CENTER);
        welcome.visibleProperty().bind(Bindings.isEmpty(vm.repos()));
        tabs.visibleProperty().bind(Bindings.isNotEmpty(vm.repos()));
        setTop(new GlobalToolbar(vm, menuBar, this::chooseAndOpen, this::cloneRepo,
                () -> SettingsDialog.show(vm), this::paletteCommands));
        setCenter(center);

        palette = new CommandPalette(this::paletteCommands);
        palette.setVisible(false);
        palette.setManaged(false);
        center.getChildren().add(palette);
        addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (e.isShortcutDown() && e.getCode() == KeyCode.K) {
                palette.show();
                e.consume();
            }
        });

        vm.repos().addListener((ListChangeListener<RepoViewModel>) c -> {
            while (c.next()) {
                for (RepoViewModel added : c.getAddedSubList()) addTab(added);
            }
            welcome.reload();
        });
        tabs.getSelectionModel().selectedItemProperty().addListener((o, a, tab) ->
                vm.selectedProperty().set(tab == null ? null : (RepoViewModel) tab.getUserData()));
        vm.selectedProperty().addListener((o, a, sel) -> {
            for (Tab t : tabs.getTabs()) if (t.getUserData() == sel) tabs.getSelectionModel().select(t);
        });
        tabs.skinProperty().addListener((o, a, skin) -> { if (skin != null) installTabDrag(); });
        setOnDragOver(e -> {
            Dragboard db = e.getDragboard();
            if (db.hasFiles() && db.getFiles().stream().anyMatch(File::isDirectory)) {
                e.acceptTransferModes(TransferMode.COPY);
            }
            e.consume();
        });
        setOnDragDropped(e -> {
            Dragboard db = e.getDragboard();
            var dir = db.getFiles().stream().filter(File::isDirectory).findFirst();
            if (dir.isPresent()) {
                Path p = dir.get().toPath();
                if (Files.exists(p.resolve(".git"))) {
                    vm.openRepository(p);
                } else if (Dialogs.confirm("Not a repository", p + " is not a git repository.\nInitialise it now?")) {
                    vm.initRepository(p);
                }
                e.setDropCompleted(true);
            }
            e.consume();
        });
    }

    /** Drag a tab header horizontally to move that repository tab. */
    private void installTabDrag() {
        tabs.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            dragFrom = e.getButton() == MouseButton.PRIMARY ? tabIndexAt(e.getSceneX(), e.getSceneY()) : -1;
            pressX = e.getSceneX();
            pressY = e.getSceneY();
        });
        tabs.addEventFilter(MouseEvent.MOUSE_RELEASED, e -> {
            int from = dragFrom;
            dragFrom = -1;
            if (from < 0) return;
            boolean moved = Math.abs(e.getSceneX() - pressX) + Math.abs(e.getSceneY() - pressY) > 20;
            int to = tabIndexAt(e.getSceneX(), e.getSceneY());
            if (!moved || to < 0 || to == from || to >= tabs.getTabs().size()) return;
            Tab t = tabs.getTabs().remove(from);
            tabs.getTabs().add(to, t);
            tabs.getSelectionModel().select(t);
        });
    }

    /** Index of the tab header under a scene point, or -1 when the point is outside every header. */
    private int tabIndexAt(double sceneX, double sceneY) {
        var headers = tabs.lookupAll(".tab").stream()
                .filter(n -> n.getParent() != null && n.getParent().getStyleClass().contains("headers-region"))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        headers.sort(java.util.Comparator.comparingDouble(n -> n.localToScene(n.getBoundsInLocal()).getMinX()));
        if (headers.isEmpty()) return -1;
        // The leftmost header belongs to this TabPane, so its row is the one drags must stay in.
        var first = headers.get(0).localToScene(headers.get(0).getBoundsInLocal());
        if (sceneY < first.getMinY() || sceneY > first.getMaxY()) return -1;
        for (int i = 0; i < headers.size(); i++) {
            var n = headers.get(i);
            if (n.localToScene(n.getBoundsInLocal()).contains(sceneX, sceneY)) return i;
        }
        return -1;
    }

    /** Repository roots in tab order, for restoring the session. */
    public java.util.List<Path> tabRoots() {
        return tabs.getTabs().stream()
                .filter(t -> t.getUserData() instanceof RepoViewModel)
                .map(t -> ((RepoViewModel) t.getUserData()).session().root())
                .toList();
    }

    /** Copies the window layout expectations into the settings: divider positions of the active tab. */
    public void saveLayout() {
        Tab t = tabs.getSelectionModel().getSelectedItem();
        if (t != null && t.getContent() instanceof RepoView rv) rv.saveLayout(vm.settingsService().get());
    }

    private void addTab(RepoViewModel repo) {
        Tab tab = new Tab(repo.name(), new RepoView(repo));
        tab.textProperty().bind(Bindings.createStringBinding(() -> label(repo), repo.worktreeProperty(),
                repo.statusProperty()));
        tab.setUserData(repo);
        tab.setTooltip(new Tooltip(repo.session().root().toString()));
        tab.setOnClosed(e -> vm.closeRepository(repo));

        MenuItem closeOthers = new MenuItem("Close others");
        closeOthers.setOnAction(e -> {
            for (Tab other : new ArrayList<>(tabs.getTabs())) {
                if (other == tab) continue;
                tabs.getTabs().remove(other);
                if (other.getUserData() instanceof RepoViewModel r) vm.closeRepository(r);
            }
        });
        MenuItem copyPath = new MenuItem("Copy path");
        copyPath.setOnAction(e -> {
            ClipboardContent cc = new ClipboardContent();
            cc.putString(repo.session().root().toString());
            Clipboard.getSystemClipboard().setContent(cc);
        });
        MenuItem explorer = new MenuItem("Open in Explorer");
        explorer.setOnAction(e -> openInExplorer(repo.session().root()));
        MenuItem terminal = new MenuItem("Open in Terminal");
        terminal.setOnAction(e -> openTerminal(repo.session().root()));
        tab.setContextMenu(new ContextMenu(closeOthers, copyPath, new SeparatorMenuItem(), explorer, terminal));

        tabs.getTabs().add(tab);
        tabs.getSelectionModel().select(tab);
    }

    /** Tab title: name, worktree marker, number of changed files and ahead/behind counts. */
    private static String label(RepoViewModel repo) {
        StringBuilder sb = new StringBuilder(repo.name());
        if (repo.worktreeProperty().get()) sb.append(" (worktree)");
        RepoStatus s = repo.statusProperty().get();
        if (s != null) {
            long dirty = s.entries().stream().filter(e -> e.kind() != StatusEntry.Kind.IGNORED).count();
            if (dirty > 0) sb.append(" ●").append(dirty);
            if (s.ahead() > 0 || s.behind() > 0) sb.append(" ↑").append(s.ahead()).append(" ↓").append(s.behind());
        }
        return sb.toString();
    }

    private static void openInExplorer(Path dir) {
        try {
            com.github.gitguiorg.gitgui.util.SystemActions.open(dir);
        } catch (IOException | RuntimeException e) {
            Fx.showError("Cannot open the folder", e);
        }
    }

    private static void openTerminal(Path dir) {
        try {
            com.github.gitguiorg.gitgui.util.SystemActions.openTerminal(dir);
        } catch (IOException e) {
            Fx.showError("Cannot open a terminal", e);
        }
    }

    private MenuBar buildMenu() {
        MenuItem open = new MenuItem("Open Repository…");
        open.setAccelerator(new KeyCodeCombination(KeyCode.O, KeyCombination.SHORTCUT_DOWN));
        open.setOnAction(e -> chooseAndOpen());
        MenuItem cloneItem = new MenuItem("Clone Repository…");
        cloneItem.setAccelerator(new KeyCodeCombination(KeyCode.O, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN));
        cloneItem.setOnAction(e -> cloneRepo());
        MenuItem init = new MenuItem("Init Repository…");
        init.setOnAction(e -> chooseAndInit());
        MenuItem applyPatch = new MenuItem("Apply patch…");
        applyPatch.setOnAction(e -> applyPatch());
        applyPatch.disableProperty().bind(vm.selectedProperty().isNull());
        MenuItem fetchAll = new MenuItem("Fetch All Repositories");
        fetchAll.setAccelerator(new KeyCodeCombination(KeyCode.F, KeyCombination.SHORTCUT_DOWN,
                KeyCombination.SHIFT_DOWN));
        fetchAll.setOnAction(e -> vm.repos().forEach(r -> r.network().fetch(false)));
        fetchAll.disableProperty().bind(Bindings.isEmpty(vm.repos()));

        MenuItem fsck = new MenuItem("Check repository (fsck)");
        fsck.setOnAction(e -> {
            RepoViewModel sel = vm.selectedProperty().get();
            if (sel == null) return;
            sel.session().async(() -> sel.session().git().fsck()).whenComplete((out, err) -> Fx.run(() -> {
                if (err != null) { sel.reportError(err); return; }
                showOutput("Repository check", out);
            }));
        });
        MenuItem gc = new MenuItem("Run git gc");
        gc.setOnAction(e -> {
            RepoViewModel sel = vm.selectedProperty().get();
            if (sel != null) sel.run(() -> sel.session().git().gc());
        });
        MenuItem prune = new MenuItem("Prune unreachable objects");
        prune.setOnAction(e -> {
            RepoViewModel sel = vm.selectedProperty().get();
            if (sel != null && Dialogs.confirm("Prune unreachable objects",
                    "Remove the loose objects that are not reachable from any ref?\nThis cannot be undone.")) {
                sel.run(() -> sel.session().git().prune());
            }
        });
        Menu maintenance = new Menu("Maintenance", null, fsck, gc, prune);
        maintenance.disableProperty().bind(vm.selectedProperty().isNull());
        javafx.scene.control.CheckMenuItem gitButler = new javafx.scene.control.CheckMenuItem("GitButler for this repository");
        gitButler.disableProperty().bind(vm.selectedProperty().isNull());
        Runnable syncButler = () -> {
            RepoViewModel sel = vm.selectedProperty().get();
            gitButler.setSelected(sel != null && sel.butEnabledProperty().get());
        };
        vm.selectedProperty().addListener((o, a, sel) -> syncButler.run());
        gitButler.setOnAction(e -> {
            RepoViewModel sel = vm.selectedProperty().get();
            if (sel == null) return;
            sel.setButEnabled(gitButler.isSelected());
            vm.settingsService().save();
        });
        MenuItem close = new MenuItem("Close Tab");
        close.setAccelerator(new KeyCodeCombination(KeyCode.W, KeyCombination.SHORTCUT_DOWN));
        close.setOnAction(e -> closeCurrentTab());
        close.disableProperty().bind(Bindings.isEmpty(vm.repos()));
        MenuItem explorerItem = new MenuItem("Open in Explorer");
        explorerItem.disableProperty().bind(vm.selectedProperty().isNull());
        explorerItem.setOnAction(e -> openInExplorer(vm.selectedProperty().get().session().root()));
        MenuItem terminalItem = new MenuItem("Open in Terminal");
        terminalItem.disableProperty().bind(vm.selectedProperty().isNull());
        terminalItem.setOnAction(e -> openTerminal(vm.selectedProperty().get().session().root()));
        MenuItem exit = new MenuItem("Exit");
        exit.setOnAction(e -> getScene().getWindow().hide());

        recentMenu.setOnShowing(e -> {
            recentMenu.getItems().clear();
            for (String p : vm.recentRepos()) {
                MenuItem mi = new MenuItem(p);
                mi.setOnAction(a -> vm.openRepository(Path.of(p)));
                recentMenu.getItems().add(mi);
            }
            if (recentMenu.getItems().isEmpty()) {
                MenuItem none = new MenuItem("(none)");
                none.setDisable(true);
                recentMenu.getItems().add(none);
            }
        });
        recentMenu.getItems().add(new MenuItem("(none)"));

        MenuItem settingsItem = new MenuItem("Settings…");
        settingsItem.setAccelerator(new KeyCodeCombination(KeyCode.COMMA, KeyCombination.SHORTCUT_DOWN));
        settingsItem.setOnAction(e -> SettingsDialog.show(vm));
        MenuItem shortcuts = new MenuItem("Keyboard shortcuts");
        shortcuts.setOnAction(e -> showShortcuts());
        MenuItem paletteItem = new MenuItem("Command palette");
        paletteItem.setAccelerator(new KeyCodeCombination(KeyCode.K, KeyCombination.SHORTCUT_DOWN));
        paletteItem.setOnAction(e -> palette.show());
        MenuItem about = new MenuItem("About " + AppInfo.NAME);
        about.setOnAction(e -> showAbout());
        Menu help = new Menu("Help", null, paletteItem, shortcuts, new SeparatorMenuItem(), about);
        Menu file = new Menu("File", null, open, cloneItem, init, applyPatch, fetchAll, maintenance, gitButler, recentMenu,
                new SeparatorMenuItem(), close, explorerItem, terminalItem, new SeparatorMenuItem(), settingsItem,
                new SeparatorMenuItem(), exit);
        return new MenuBar(file, help);
    }

    /** Actions offered by the command palette: global ones plus the selected repository's. */
    private java.util.List<CommandPalette.Command> paletteCommands() {
        List<CommandPalette.Command> out = new ArrayList<>();
        out.add(new CommandPalette.Command("Open repository…", "Repository", "Ctrl+O", this::chooseAndOpen));
        out.add(new CommandPalette.Command("Clone repository…", "Repository", "Ctrl+Shift+O", this::cloneRepo));
        out.add(new CommandPalette.Command("Initialise repository…", "Repository", "", this::chooseAndInit));
        for (String path : vm.recentRepos()) {
            out.add(new CommandPalette.Command("Open " + path, "Recent", "", () -> vm.openRepository(Path.of(path))));
        }
        out.add(new CommandPalette.Command("Fetch all repositories", "Network", "Ctrl+Shift+F",
                () -> vm.repos().forEach(r -> r.network().fetch(false))));
        out.add(new CommandPalette.Command("Settings…", "Application", "Ctrl+,", () -> SettingsDialog.show(vm)));
        out.add(new CommandPalette.Command("Keyboard shortcuts", "Help", "", this::showShortcuts));
        out.add(new CommandPalette.Command("About " + AppInfo.NAME, "Help", "", this::showAbout));

        RepoViewModel repo = vm.selectedProperty().get();
        if (repo == null) return out;

        for (GitRef ref : repo.refs()) {
            if (ref.type() == GitRef.Type.LOCAL_BRANCH) {
                out.add(new CommandPalette.Command("Checkout " + ref.shortName(), "Branch", "",
                        () -> repo.checkout(ref)));
            }
        }
        for (com.github.gitguiorg.gitgui.ui.viewmodel.LogViewModel.CommitRow row : repo.log().rows()) {
            Commit c = row.commit();
            out.add(new CommandPalette.Command("Commit " + c.shortHash() + " " + c.subject(), "Commit", "", () -> {
                repo.log().selectedProperty().set(c);
                currentRepoView().ifPresent(RepoView::selectHistoryTab);
            }));
        }
        java.util.LinkedHashSet<String> files = new java.util.LinkedHashSet<>();
        repo.changes().staged().forEach(e -> files.add(e.path()));
        repo.changes().unstaged().forEach(e -> files.add(e.path()));
        for (String path : files) {
            out.add(new CommandPalette.Command("File " + path, "File", "", () -> repo.openFileHistory(path)));
        }

        out.add(new CommandPalette.Command("Checkout branch…", "Branch", "", () -> checkoutBranch(repo)));
        out.add(new CommandPalette.Command("New branch…", "Branch", "",
                () -> Dialogs.askBranch("HEAD").ifPresent(r -> repo.createBranch(r.name(), "HEAD", r.checkout()))));
        out.add(new CommandPalette.Command("Commit changes…", "Commit", repo.isButWorkspace() ? "C" : "",
                () -> currentRepoView().ifPresent(v -> {
                    if (repo.isButWorkspace()) v.focusComposer();
                    else v.focusCommit();
                })));
        out.add(new CommandPalette.Command("Search commits", "Commit", "Ctrl+F",
                () -> currentRepoView().ifPresent(RepoView::focusSearch)));
        out.add(new CommandPalette.Command("Show history tab", "Navigation", "Ctrl+1",
                () -> currentRepoView().ifPresent(RepoView::selectHistoryTab)));
        out.add(new CommandPalette.Command("Show changes tab", "Navigation", "Ctrl+2",
                () -> currentRepoView().ifPresent(RepoView::selectChangesTab)));
        out.add(new CommandPalette.Command("Toggle details and diff", "Navigation", "Ctrl+D",
                () -> currentRepoView().ifPresent(RepoView::toggleDetails)));
        out.add(new CommandPalette.Command("Open GitButler workspace", "GitButler", "", repo::openGitButler));
        out.add(new CommandPalette.Command("Fetch", "Network", "", () -> repo.network().fetch(false)));
        out.add(new CommandPalette.Command("Pull", "Network", "", repo.network()::pull));
        out.add(new CommandPalette.Command("Push", "Network", "", repo.network()::push));
        if (repo.isButWorkspace()) {
            out.add(new CommandPalette.Command("Pull GitButler workspace", "Network", "",
                    () -> repo.gitButler().pullWorkspace()));
        }
        out.add(new CommandPalette.Command("Toggle GitButler for this repository", "GitButler", "", () -> {
            repo.setButEnabled(!repo.butEnabledProperty().get());
            vm.settingsService().save();
        }));
        out.add(new CommandPalette.Command("Open in Explorer", "Repository", "",
                () -> openInExplorer(repo.session().root())));
        out.add(new CommandPalette.Command("Open in Terminal", "Repository", "",
                () -> openTerminal(repo.session().root())));
        out.add(new CommandPalette.Command("Close tab", "Repository", "Ctrl+W", this::closeCurrentTab));
        out.add(new CommandPalette.Command("Apply patch…", "Repository", "", this::applyPatch));
        out.add(new CommandPalette.Command("Check repository (fsck)", "Maintenance", "", () -> {
            repo.session().async(() -> repo.session().git().fsck()).whenComplete((text, err) -> Fx.run(() -> {
                if (err != null) { repo.reportError(err); return; }
                showOutput("Repository check", text);
            }));
        }));
        out.add(new CommandPalette.Command("Run git gc", "Maintenance", "",
                () -> repo.run(() -> repo.session().git().gc())));
        out.add(new CommandPalette.Command("Prune unreachable objects", "Maintenance", "", () -> {
            if (Dialogs.confirm("Prune unreachable objects",
                    "Remove the loose objects that are not reachable from any ref?\nThis cannot be undone.")) {
                repo.run(() -> repo.session().git().prune());
            }
        }));
        return out;
    }

    /** The view of the selected repository tab, when the tab holds a repository. */
    private java.util.Optional<RepoView> currentRepoView() {
        Tab t = tabs.getSelectionModel().getSelectedItem();
        return t != null && t.getContent() instanceof RepoView rv
                ? java.util.Optional.of(rv) : java.util.Optional.empty();
    }

    /** Closes the selected tab and its repository. */
    private void closeCurrentTab() {
        Tab t = tabs.getSelectionModel().getSelectedItem();
        if (t == null) return;
        tabs.getTabs().remove(t);
        vm.closeRepository((RepoViewModel) t.getUserData());
    }

    /** Chooses one of the local branches and checks it out. */
    private void checkoutBranch(RepoViewModel repo) {
        List<GitRef> branches = repo.refs().stream()
                .filter(r -> r.type() == GitRef.Type.LOCAL_BRANCH)
                .sorted(java.util.Comparator.comparing(GitRef::shortName))
                .toList();
        if (branches.isEmpty()) {
            Fx.showError("Checkout branch", new IllegalStateException("No local branches to check out."));
            return;
        }
        Dialogs.choose("Checkout branch", "Switch to branch",
                branches.stream().map(GitRef::shortName).toList()).ifPresent(name -> branches.stream()
                        .filter(r -> r.shortName().equals(name)).findFirst().ifPresent(repo::checkout));
    }

    private File chooseDir(String title) {
        DirectoryChooser dc = new DirectoryChooser();
        dc.setTitle(title);
        Window w = getScene() == null ? null : getScene().getWindow();
        return dc.showDialog(w);
    }

    private void chooseAndOpen() {
        File f = chooseDir("Open repository");
        if (f != null) vm.openRepository(f.toPath());
    }

    /** Shows a potentially long command output in an expanded, read-only text area. */
    private void showOutput(String title, String text) {
        Alert a = new Alert(Alert.AlertType.INFORMATION);
        a.setTitle(title);
        a.setHeaderText(title);
        TextArea area = new TextArea(text == null || text.isBlank() ? "No output." : text);
        area.setEditable(false);
        area.setPrefRowCount(12);
        area.setPrefColumnCount(80);
        a.getDialogPane().setExpandableContent(area);
        a.getDialogPane().setExpanded(true);
        a.show();
    }

    private void showAbout() {
        String javafx = System.getProperty("javafx.runtime.version", System.getProperty("javafx.version", "-"));
        Alert a = new Alert(Alert.AlertType.INFORMATION, String.join("\n",
                "Git GUI desktop client (JavaFX, git CLI backend)",
                "",
                "Java " + System.getProperty("java.version"),
                "JavaFX " + javafx,
                vm.gitVersion(),
                "GitButler CLI: " + vm.butVersion()), ButtonType.OK);
        a.setTitle("About " + AppInfo.NAME);
        a.setHeaderText(AppInfo.NAME + " " + AppInfo.VERSION);
        a.show();
    }

    private void showShortcuts() {
        Alert a = new Alert(Alert.AlertType.INFORMATION, String.join("\n",
                "Ctrl+K          Command palette",
                "Ctrl+O          Open repository",
                "Ctrl+Shift+O    Clone repository",
                "Ctrl+W          Close tab",
                "Ctrl+,          Settings",
                "Ctrl+F          Search commits",
                "Ctrl+D          Toggle diff panel",
                "Ctrl+Shift+F    Fetch all repositories",
                "Ctrl+1 / Ctrl+2 History / Changes tab",
                "F5              Refresh",
                "C               Commit composer (GitButler workspace)",
                "Ctrl+Enter      Commit (composer and message box)"), ButtonType.OK);
        a.setTitle("Keyboard shortcuts");
        a.setHeaderText("Keyboard shortcuts");
        a.getDialogPane().setStyle("-fx-font-family: 'Consolas', monospace;");
        a.show();
    }

    private void cloneRepo() {
        CloneDialog.show(vm);
    }

    /** Chooses a patch, previews it with git apply --check --stat, then applies it as diff or mailbox. */
    private void applyPatch() {
        RepoViewModel repo = vm.selectedProperty().get();
        if (repo == null) return;
        FileChooser fc = new FileChooser();
        fc.setTitle("Apply patch");
        fc.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Patches", "*.patch", "*.diff", "*.mbox"),
                new FileChooser.ExtensionFilter("All files", "*"));
        File f = fc.showOpenDialog(getScene() == null ? null : getScene().getWindow());
        if (f == null) return;
        Path path = f.toPath();
        repo.session().async(() -> repo.session().git().patchInfo(path)).whenComplete((info, err) -> Fx.run(() -> {
            if (err != null) { Fx.showError("Cannot read the patch", err); return; }
            previewPatch(repo, path, info);
        }));
    }

    private void previewPatch(RepoViewModel repo, Path path,
                              com.github.gitguiorg.gitgui.git.GitClient.PatchInfo info) {
        TextArea text = new TextArea();
        text.setEditable(false);
        text.setWrapText(false);
        text.setPrefRowCount(14);
        text.setPrefColumnCount(70);
        text.setStyle("-fx-font-family: 'Consolas', monospace;");
        text.setText(info.applies() ? info.stat() : info.error() + "\n\n" + info.stat());
        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle("Apply patch");
        d.setHeaderText(path.getFileName() + (info.mailbox() ? " · mailbox (git am)" : " · diff (git apply)")
                + (info.applies() ? " · applies cleanly" : " · does not apply"));
        d.getDialogPane().setContent(text);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        d.getDialogPane().lookupButton(ButtonType.OK).setDisable(!info.applies());
        d.showAndWait().filter(b -> b == ButtonType.OK).ifPresent(b -> repo.applyPatch(path, info.mailbox()));
    }

    private void chooseAndInit() {
        File f = chooseDir("Select folder to initialise");
        if (f != null) vm.initRepository(f.toPath());
    }
}