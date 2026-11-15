package com.github.gitguiorg.gitgui.ui.view;

import com.github.gitguiorg.gitgui.git.ButBranch;
import com.github.gitguiorg.gitgui.git.ButConflicts;
import com.github.gitguiorg.gitgui.git.ButPullCheck;
import com.github.gitguiorg.gitgui.git.ButStatus;
import com.github.gitguiorg.gitgui.ui.viewmodel.GitButlerViewModel;
import com.github.gitguiorg.gitgui.ui.viewmodel.GitButlerViewModel.ConflictChoice;
import com.github.gitguiorg.gitgui.util.DateFormats;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.RadioButton;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.util.Duration;
import org.kordamp.ikonli.javafx.FontIcon;

/** Structured GitButler workspace ({@code but --json status}) with a refresh action and raw fallback. */
public final class GitButlerView extends BorderPane {
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);
    private static final DateTimeFormatter FULL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
    private static final String CARD_STYLE = "-fx-background-color: #161b22;"
            + " -fx-background-radius: 10; -fx-border-color: rgba(99,102,241,0.20);"
            + " -fx-border-width: 1; -fx-border-radius: 10; -fx-padding: 8 10 8 10;";
    private static final String MUTED = "-fx-opacity: 0.65;";
    private static final String NAME_TEXT = "-fx-fill: #e6edf3; -fx-font-weight: bold;";
    private static final String META_TEXT = "-fx-fill: #8b949e;";
    private static final String ALERT_CARD_STYLE = "-fx-background-color: rgba(248,81,73,0.06);"
            + " -fx-background-radius: 10; -fx-border-color: rgba(248,81,73,0.55);"
            + " -fx-border-width: 1; -fx-border-radius: 10; -fx-padding: 8 10 8 10;";
    private static final long LOADING_THRESHOLD_MS = 300;

    private final GitButlerViewModel vm;
    private final VBox content = new VBox(6);
    private final ScrollPane scroll = new ScrollPane(content);
    private final Set<String> selectedPaths = new LinkedHashSet<>();
    private final Set<String> selectedCommits = new LinkedHashSet<>();
    private final javafx.beans.property.BooleanProperty draggingBranch = new javafx.beans.property.SimpleBooleanProperty();
    private String commitFilter = "";
    private String unappliedFilter = "";
    private long loadingStartTime = -1;
    private VBox newBranchBar;
    private TextField newBranchName;
    private RadioButton newBranchAbove;
    private RadioButton newBranchBelow;
    private Label newBranchHint;
    private String newBranchAnchor;
    private boolean newBranchStacked;
    private TextField branchFilterField;
    private final Label branchFilterHint = new Label();
    private final PauseTransition branchFilterHintTimer = new PauseTransition(Duration.seconds(4));
    private boolean rebuildPending;

    public GitButlerView(GitButlerViewModel vm) {
        this.vm = vm;
        Label title = new Label("GitButler");
        title.setStyle("-fx-font-weight: bold;");
        Label executable = new Label();
        executable.setMinWidth(0);
        executable.setTooltip(new Tooltip(vm.executable().isBlank() ? "but not found" : vm.executable()));
        executable.setGraphic(new FontIcon("mdi2i-information-outline"));
        executable.setOpacity(0.6);

        Label updated = new Label();
        updated.setOpacity(0.7);
        updated.textProperty().bind(Bindings.createStringBinding(() -> {
            LocalDateTime at = vm.updatedAtProperty().get();
            return at == null ? "" : "Updated " + CLOCK.format(at);
        }, vm.updatedAtProperty()));
        Tooltip updatedTip = new Tooltip();
        updatedTip.textProperty().bind(Bindings.createStringBinding(() -> {
            LocalDateTime at = vm.updatedAtProperty().get();
            return at == null ? "" : "Last updated: " + FULL.format(at);
        }, vm.updatedAtProperty()));
        updated.tooltipProperty().bind(Bindings.createObjectBinding(
                () -> vm.updatedAtProperty().get() == null ? null : updatedTip, vm.updatedAtProperty()));

        ProgressIndicator busy = new ProgressIndicator();
        busy.setPrefSize(14, 14);
        busy.setMaxSize(14, 14);
        busy.setMouseTransparent(true);
        busy.setStyle("-fx-progress-color: #79c0ff;");
        busy.setTooltip(new Tooltip("GitButler operation in progress"));
        busy.visibleProperty().bind(Bindings.or(vm.loadingProperty(), vm.actionBusyProperty()));
        busy.managedProperty().bind(busy.visibleProperty());

        Button pull = new Button(null, new FontIcon("mdi2a-arrow-down-bold-outline"));
        pull.setTooltip(new Tooltip("Pull remote: fetch the latest target and rebase the applied branches onto it"));
        pull.setOnAction(e -> checkPull(vm));
        pull.disableProperty().bind(Bindings.or(vm.actionBusyProperty(), vm.loadingProperty()));

        Button refresh = new Button(null, new FontIcon("mdi2r-refresh"));
        refresh.setTooltip(new Tooltip("Refresh the GitButler status"));
        refresh.setOnAction(e -> vm.load());
        refresh.disableProperty().bind(vm.loadingProperty());

        Region headSpacer = new Region();
        HBox.setHgrow(headSpacer, Priority.ALWAYS);
        HBox header = new HBox(6, title, executable, updated, busy, headSpacer, pull, refresh);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(3, 6, 3, 6));

        branchFilterHint.setWrapText(true);
        branchFilterHint.setMaxWidth(Double.MAX_VALUE);
        branchFilterHint.setPadding(new Insets(5, 8, 5, 8));
        branchFilterHint.setStyle("-fx-background-color: rgba(56,139,253,0.12);"
                + " -fx-border-color: rgba(56,139,253,0.35); -fx-border-width: 0 0 1 0;");
        branchFilterHint.setVisible(false);
        branchFilterHint.setManaged(false);
        branchFilterHintTimer.setOnFinished(e -> hideBranchFilterHint());

        Label actionText = new Label();
        actionText.setWrapText(true);
        actionText.setMaxWidth(Double.MAX_VALUE);
        actionText.textProperty().bind(vm.actionMessageProperty());
        HBox.setHgrow(actionText, Priority.ALWAYS);
        Label actionMark = new Label("✓");
        actionMark.setStyle("-fx-font-weight: bold; -fx-text-fill: #3fb950;");
        Button dismiss = new Button("×");
        dismiss.setFocusTraversable(false);
        dismiss.setTooltip(new Tooltip("Dismiss this message"));
        dismiss.setStyle("-fx-background-color: transparent; -fx-padding: 0 4 0 4; -fx-font-size: 13px;");
        dismiss.setOnAction(e -> vm.clearActionMessage());
        HBox actionBanner = new HBox(8, actionMark, actionText, dismiss);
        actionBanner.setAlignment(Pos.CENTER_LEFT);
        actionBanner.setPadding(new Insets(5, 8, 5, 8));
        actionBanner.setStyle("-fx-background-color: rgba(46,160,67,0.12);"
                + " -fx-border-color: rgba(46,160,67,0.35); -fx-border-width: 0 0 1 0;");
        actionBanner.visibleProperty().bind(vm.actionMessageProperty().isNotEmpty());
        actionBanner.managedProperty().bind(actionBanner.visibleProperty());
        PauseTransition hideMessage = new PauseTransition(Duration.seconds(6));
        hideMessage.setOnFinished(e -> vm.clearActionMessage());
        vm.actionMessageProperty().addListener((o, a, b) -> {
            if (b == null || b.isBlank()) hideMessage.stop();
            else hideMessage.playFromStart();
        });

        content.setPadding(new Insets(6, 8, 8, 8));
        content.setFillWidth(true);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");

        newBranchBar = buildNewBranchBar(vm);
        newBranchBar.setVisible(false);
        newBranchBar.setManaged(false);
        setTop(new VBox(header, branchFilterHint, actionBanner, newBranchBar));
        // Left: uncommitted changes and stacks. Right: the git graph, as in History, with the stacks tagged S1, S2...
        LogPane graph = new LogPane(vm.repo());
        graph.setMinWidth(300);
        TextField commitSearch = new TextField();
        commitSearch.setPromptText("Filter commits in stacks…  (message, hash, author)");
        commitSearch.textProperty().addListener((o, a, b) -> {
            commitFilter = b == null ? "" : b;
            refreshRowStyles();
        });
        commitSearch.setOnKeyPressed(e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) { commitSearch.clear(); e.consume(); }
        });
        VBox leftPane = new VBox(4, commitSearch, scroll);
        leftPane.setPadding(new Insets(3, 4, 0, 4));
        VBox.setVgrow(scroll, Priority.ALWAYS);
        leftPane.setMinWidth(300);
        // Dragging near the top or bottom edge scrolls the stacks, so a commit can be dropped on a distant branch.
        scroll.addEventFilter(javafx.scene.input.DragEvent.DRAG_OVER, e -> {
            double edge = 36;
            double v = scroll.getVvalue();
            if (e.getY() < edge) scroll.setVvalue(Math.max(0, v - 0.015));
            else if (e.getY() > scroll.getHeight() - edge) scroll.setVvalue(Math.min(1, v + 0.015));
        });
        javafx.scene.control.SplitPane columns = new javafx.scene.control.SplitPane(leftPane, graph);
        columns.setDividerPositions(0.42);
        javafx.scene.control.SplitPane.setResizableWithParent(leftPane, false);
        setCenter(columns);

        vm.statusProperty().addListener((o, a, b) -> scheduleRebuild());
        vm.rawOutputProperty().addListener((o, a, b) -> scheduleRebuild());
        vm.warningProperty().addListener((o, a, b) -> scheduleRebuild());
        vm.errorProperty().addListener((o, a, b) -> scheduleRebuild());
        vm.unappliedBranchesProperty().addListener((o, a, b) -> scheduleRebuild());
        vm.branchWarningProperty().addListener((o, a, b) -> scheduleRebuild());
        vm.loadingProperty().addListener((o, a, b) -> scheduleRebuild());
        vm.loadingProperty().addListener((o, a, b) -> {
            if (Boolean.TRUE.equals(b)) loadingStartTime = System.currentTimeMillis();
            else loadingStartTime = -1;
        });
        rebuild();
    }

    /** Property changes arrive together; rebuilding once per pulse avoids flashing intermediate states. */
    private void scheduleRebuild() {
        if (rebuildPending) return;
        rebuildPending = true;
        Platform.runLater(() -> {
            rebuildPending = false;
            rebuild();
        });
    }

    private void rebuild() {
        branchFilterField = null;
        String error = vm.errorProperty().get();
        String warning = vm.warningProperty().get();
        ButStatus status = vm.statusProperty().get();
        List<Node> nodes = new ArrayList<>();

        if (error != null && !error.isBlank()) {
            nodes.add(problemCard("Could not read GitButler status", error, "#f85149"));
        } else if (status == null && warning != null && !warning.isBlank()) {
            nodes.add(problemCard("Structured view unavailable",
                    "The output of but status could not be read, so the raw JSON is shown instead.\n" + warning,
                    "#d29922"));
            if (!vm.rawOutputProperty().get().isBlank()) nodes.add(rawCard(vm.rawOutputProperty().get(), true));
        } else if (status == null) {
            nodes.add(this.placeholder());
        } else {
            nodes.add(summary(status));
            if (status.conflictedCommits() > 0) nodes.add(conflictCard(status, vm));
            nodes.add(collapsible(changesCard(status.uncommittedChanges(), vm), "changes", false));
            nodes.add(stacksCard(status.stacks(), vm));
            if (vm.branchWarningProperty().get() != null && !vm.branchWarningProperty().get().isBlank()) {
                nodes.add(problemCard("Unapplied branches unavailable",
                        vm.branchWarningProperty().get(), "#d29922"));
            }
            if (!vm.unappliedBranchesProperty().get().isEmpty()) {
                nodes.add(collapsible(unappliedBranchesCard(vm.unappliedBranchesProperty().get(), vm), "unapplied", true));
            }
            if (status.base() != null) nodes.add(collapsible(baseCard(status.base(), vm), "base", true));
            if (!vm.rawOutputProperty().get().isBlank()) nodes.add(rawCard(vm.rawOutputProperty().get(), false));
        }
        double position = scroll.getVvalue();
        content.getChildren().setAll(nodes);
        refreshRowStyles();
        if (position > 0) Platform.runLater(() -> scroll.setVvalue(position));
    }

    private final java.util.Map<String, Boolean> collapsed = new java.util.HashMap<>();

    /** Adds a chevron to the card header that hides or shows the rest of the card; the state survives rebuilds. */
    private VBox collapsible(VBox card, String key, boolean collapsedByDefault) {
        if (card.getChildren().isEmpty() || !(card.getChildren().get(0) instanceof HBox header)) return card;
        boolean[] closed = {collapsed.getOrDefault(key, collapsedByDefault)};
        Label chevron = new Label();
        chevron.setStyle("-fx-cursor: hand; -fx-opacity: 0.7;");
        Runnable apply = () -> {
            chevron.setGraphic(new FontIcon(closed[0] ? "mdi2c-chevron-right" : "mdi2c-chevron-down"));
            for (int i = 1; i < card.getChildren().size(); i++) {
                card.getChildren().get(i).setVisible(!closed[0]);
                card.getChildren().get(i).setManaged(!closed[0]);
            }
        };
        header.getChildren().add(0, chevron);
        header.setStyle("-fx-cursor: hand;");
        header.setOnMouseClicked(e -> {
            if (e.getButton() != MouseButton.PRIMARY || e.getTarget() instanceof javafx.scene.control.ButtonBase) return;
            closed[0] = !closed[0];
            collapsed.put(key, closed[0]);
            apply.run();
        });
        apply.run();
        return card;
    }

    /** Selection highlight and search filter on the commit rows, without rebuilding the panel. */
    private void refreshRowStyles() {
        String q = commitFilter.strip().toLowerCase(Locale.ROOT);
        for (Node n : content.lookupAll(".bt-commit")) {
            if (!(n.getUserData() instanceof ButStatus.Commit c)) continue;
            boolean selected = c.cliId() != null && selectedCommits.contains(c.cliId());
            boolean match = q.isEmpty() || matches(c, q);
            n.setStyle(selected ? "-fx-background-color: rgba(139,92,246,0.22); -fx-background-radius: 6;"
                    : !q.isEmpty() && match ? "-fx-background-color: rgba(210,153,34,0.20); -fx-background-radius: 6;" : "");
            n.setOpacity(q.isEmpty() || match ? 1 : 0.3);
        }
    }

    private static boolean matches(ButStatus.Commit c, String q) {
        for (String field : new String[]{c.message(), c.id(), c.cliId(), c.authorName()}) {
            if (field != null && field.toLowerCase(Locale.ROOT).contains(q)) return true;
        }
        return false;
    }

    private Node placeholder() {
        VBox box = new VBox(10);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(20));
        if (vm.loadingProperty().get()) {
            if (System.currentTimeMillis() - loadingStartTime > LOADING_THRESHOLD_MS) {
                ProgressIndicator progress = new ProgressIndicator();
                progress.setPrefSize(28, 28);
                box.getChildren().addAll(progress, muted("Reading GitButler status…"));
            } else {
                box.getChildren().add(muted("Reading GitButler status…"));
            }
        } else {
            box.getChildren().add(muted("No GitButler status yet."));
        }
        return box;
    }

    private static HBox summary(ButStatus status) {
        int branches = status.stacks().stream().mapToInt(s -> s.branches().size()).sum();
        int commits = status.stacks().stream().flatMap(s -> s.branches().stream())
                .mapToInt(b -> b.commits().size()).sum();
        HBox bar = new HBox(8,
                pill(count(status.uncommittedChanges().size(), "change", "changes"), "amber"),
                pill(count(status.stacks().size(), "stack", "stacks"), "gray"),
                pill(count(branches, "branch", "branches"), "blue"),
                pill(count(commits, "commit", "commits"), "green"));
        if (status.behind() > 0) bar.getChildren().add(pill(status.behind() + " behind", "red"));
        int conflicted = status.conflictedCommits();
        if (conflicted > 0) bar.getChildren().add(pill(count(conflicted, "conflicted commit", "conflicted commits"), "red"));
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    private VBox changesCard(List<ButStatus.Change> changes, GitButlerViewModel vm) {
        VBox card = card();
        Set<String> available = new LinkedHashSet<>();
        for (ButStatus.Change change : changes) {
            if (change.path() != null && !change.path().isBlank()) available.add(change.path());
        }
        selectedPaths.retainAll(available);
        int selected = selectedPaths.size();

        Label heading = new Label("Uncommitted changes");
        heading.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;");
        HBox header = new HBox(8, heading,
                pill(count(changes.size(), "change", "changes"), changes.isEmpty() ? "gray" : "amber"));
        header.setAlignment(Pos.CENTER_LEFT);
        card.getChildren().add(header);
        if (changes.isEmpty()) {
            selectedPaths.clear();
            card.getChildren().add(muted("No uncommitted changes."));
            return card;
        }

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        CheckBox selectAll = new CheckBox("Select all");
        selectAll.setSelected(!available.isEmpty() && selected == available.size());
        selectAll.setIndeterminate(selected > 0 && selected < available.size());
        if (available.isEmpty()) {
            selectAll.setDisable(true);
        } else {
            selectAll.disableProperty().bind(vm.actionBusyProperty());
            selectAll.setOnAction(e -> {
                if (selectAll.isSelected()) selectedPaths.addAll(available);
                else selectedPaths.clear();
                scheduleRebuild();
            });
        }
        header.getChildren().addAll(spacer, selectAll);

        Button commit = new Button(selected == 0
                ? "Commit all"
                : "Commit " + selected + " selected");
        commit.setStyle("-fx-font-weight: bold;");
        commit.setTooltip(new Tooltip("Opens the commit composer at the bottom with this scope; Ctrl+Enter commits"));
        commit.disableProperty().bind(vm.actionBusyProperty());
        commit.setOnAction(e -> vm.repo().composeCommit(
                selected == 0 ? List.of() : List.copyOf(selectedPaths), null));
        HBox actions = new HBox(8);
        actions.setAlignment(Pos.CENTER_LEFT);
        Region actionSpacer = new Region();
        HBox.setHgrow(actionSpacer, Priority.ALWAYS);
        actions.getChildren().addAll(selected == 0
                ? muted("Select files for bulk actions.")
                : pill(count(selected, "file selected", "files selected"), "blue"), actionSpacer);
        if (selected > 0) {
            List<String> selectedList = List.copyOf(selectedPaths);
            Button discard = new Button("Discard selected…");
            discard.disableProperty().bind(vm.actionBusyProperty());
            discard.setOnAction(e -> confirmDiscardSelected(vm, selectedList));
            actions.getChildren().add(discard);
        }
        actions.getChildren().add(commit);
        card.getChildren().add(actions);

        VBox rows = new VBox(0);
        for (ButStatus.Change change : changes) rows.getChildren().add(changeRow(change, vm));
        card.getChildren().add(rows);
        return card;
    }

    private HBox changeRow(ButStatus.Change change, GitButlerViewModel vm) {
        CheckBox pick = new CheckBox();
        String changePath = change.path();
        if (changePath == null || changePath.isBlank()) {
            pick.setDisable(true);
        } else {
            pick.setSelected(selectedPaths.contains(changePath));
            pick.setOnAction(e -> {
                if (pick.isSelected()) selectedPaths.add(changePath);
                else selectedPaths.remove(changePath);
                scheduleRebuild();
            });
            pick.disableProperty().bind(vm.actionBusyProperty());
        }
        String tone = changeTone(change.changeType());
        Label mark = new Label(changeMark(change.changeType()));
        mark.setMinWidth(22);
        mark.setAlignment(Pos.CENTER);
        mark.setStyle("-fx-font-family: 'Consolas', monospace; -fx-font-size: 11px; -fx-font-weight: bold;"
                + " -fx-text-fill: " + foreground(tone) + "; -fx-background-color: " + background(tone)
                + "; -fx-background-radius: 4;");

        Label path = new Label(change.path().isBlank() ? "(unknown path)" : change.path());
        path.setStyle("-fx-font-family: 'Consolas', monospace;");
        path.setMaxWidth(Double.MAX_VALUE);
        path.setTextOverrun(OverrunStyle.CENTER_ELLIPSIS);
        path.setTooltip(new Tooltip(change.path()));
        HBox.setHgrow(path, Priority.ALWAYS);

        Label type = new Label(change.changeType());
        type.setStyle("-fx-font-size: 11px; -fx-opacity: 0.65;");
        HBox row = new HBox(8, pick, mark, path, type, cliId(change.cliId()));
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(0));

        List<MenuItem> menuItems = changeMenuItems(change, vm, changePath);
        attachMenu(row, new ContextMenu(menuItems.toArray(MenuItem[]::new)));
        row.getChildren().add(actionsButton(changeMenuItems(change, vm, changePath)));
        if (changePath != null && !changePath.isBlank()) {
            row.setOnDragDetected(e -> {
                if (e.getButton() != MouseButton.PRIMARY) return;
                List<String> ids = selectedPaths.contains(changePath) ? List.copyOf(selectedPaths) : List.of(changePath);
                ButDnd.start(row, e, new ButDnd.Payload(ButDnd.Kind.FILE, ids));
            });
            row.setOnDragDone(e -> ButDnd.finish());
        }
        boolean deleted = "deleted".equalsIgnoreCase(change.changeType());
        row.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2 && !deleted) {
                vm.openFile(change.path());
            } else if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 1 && !change.path().isBlank()) {
                // Same diff pane as History/Changes.
                boolean added = "added".equalsIgnoreCase(change.changeType()) || "untracked".equalsIgnoreCase(change.changeType());
                vm.repo().diff().showFile(new com.github.gitguiorg.gitgui.model.StatusEntry(
                        added ? com.github.gitguiorg.gitgui.model.StatusEntry.Kind.UNTRACKED
                                : com.github.gitguiorg.gitgui.model.StatusEntry.Kind.ORDINARY,
                        (char) 46, added ? (char) 63 : (char) 77, change.path(), null), false);
            }
        });
        return row;
    }

    private VBox stacksCard(List<ButStatus.Stack> stacks, GitButlerViewModel vm) {
        VBox card = card();
        HBox header = cardHeader("Applied stacks",
                "branches applied to the workspace · use Actions to stack, move or unapply a branch");
        if (stacks.isEmpty()) {
            Button create = new Button("New branch…");
            create.setOnAction(e -> showNewBranchBar(null));
            create.disableProperty().bind(vm.actionBusyProperty());
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            header.getChildren().addAll(spacer, create);
            card.getChildren().addAll(header, muted("No GitButler branches are applied."));
            return card;
        }
        card.getChildren().add(header);
        VBox groups = new VBox(10);
        int number = 0;
        for (ButStatus.Stack stack : stacks) {
            if (stack.branches().isEmpty()) continue;
            number++;
            VBox group = new VBox(4);
            String stackColor = CommitCell.stackColor(number);
            group.setStyle("-fx-border-color: transparent transparent transparent " + stackColor + ";"
                    + " -fx-border-width: 0 0 0 3; -fx-padding: 0 0 0 6;");
            String names = stack.branches().stream().map(ButStatus.Branch::name)
                    .filter(n -> n != null && !n.isBlank()).reduce((a, b) -> a + " → " + b).orElse("");
            Label stackTitle = new Label("S" + number);
            stackTitle.setStyle("-fx-font-size: 10px; -fx-font-weight: bold; -fx-text-fill: white; -fx-padding: 0 5 0 5;"
                    + " -fx-background-radius: 3; -fx-background-color: " + stackColor + ";");
            Label stackNames = muted(names);
            stackNames.setMinWidth(0);
            stackNames.setTextOverrun(OverrunStyle.CENTER_ELLIPSIS);
            stackNames.setMaxWidth(320);
            stackNames.setTooltip(new Tooltip(names));
            HBox stackHead = new HBox(8, stackTitle, stackNames);
            stackHead.setAlignment(Pos.CENTER_LEFT);
            String top = stack.branches().get(0).name();
            Button create = new Button(null, new FontIcon("mdi2p-plus"));
            create.setTooltip(new Tooltip("New stacked branch on top of this stack"));
            create.setOnAction(e -> showNewBranchBar(top));
            if (top == null || top.isBlank()) create.setDisable(true);
            else create.disableProperty().bind(vm.actionBusyProperty());
            Region stackSpacer = new Region();
            HBox.setHgrow(stackSpacer, Priority.ALWAYS);
            stackHead.getChildren().add(stackSpacer);
            boolean canPush = top != null && !top.isBlank()
                    && stack.branches().stream().anyMatch(branch -> needsPush(branch.status()));
            if (canPush) {
                Button push = new Button(null, new FontIcon("mdi2a-arrow-up"));
                push.setTooltip(new Tooltip("Push stack"));
                push.setOnAction(e -> confirmPushStack(vm, top, stack.branches().size()));
                push.disableProperty().bind(vm.actionBusyProperty());
                stackHead.getChildren().add(push);
            }
            stackHead.getChildren().add(create);
            group.getChildren().add(stackHead);
            for (ButStatus.Branch branch : stack.branches()) group.getChildren().add(branchCard(branch, vm, stackColor));
            groups.getChildren().add(group);
        }
        if (groups.getChildren().isEmpty()) groups.getChildren().add(muted("No branches in this stack."));
        card.getChildren().add(groups);
        Label unstack = new Label("Drop a branch here to make it independent");
        unstack.setMaxWidth(Double.MAX_VALUE);
        unstack.setAlignment(Pos.CENTER);
        unstack.setPadding(new Insets(8));
        unstack.setStyle("-fx-border-color: #8b949e; -fx-border-style: dashed; -fx-border-radius: 6; -fx-opacity: 0.8;");
        unstack.visibleProperty().bind(draggingBranch);
        unstack.managedProperty().bind(draggingBranch);
        unstack.setOnDragOver(e -> ButDnd.accept(e, ButDnd.Kind.BRANCH));
        unstack.setOnDragDropped(e -> {
            ButDnd.Payload p = ButDnd.current();
            if (p == null || p.kind() != ButDnd.Kind.BRANCH) return;
            vm.unstackBranch(p.ids().get(0));
            e.setDropCompleted(true);
            e.consume();
        });
        card.getChildren().add(unstack);
        return card;
    }

    private VBox branchCard(ButStatus.Branch branch, GitButlerViewModel vm, String color) {
        boolean landed = "integrated".equalsIgnoreCase(branch.status());
        VBox card = new VBox(3);
        card.setPadding(new Insets(4, 6, 4, 6));
        card.setStyle("-fx-background-color: rgba(110,118,129,0.08); -fx-background-radius: 6;");

        FontIcon icon = new FontIcon("mdi2s-source-branch");
        icon.setIconSize(14);
        icon.setIconColor(javafx.scene.paint.Color.web(color));
        Label name = new Label(branch.name().isBlank() ? "(unnamed branch)" : branch.name());
        name.setStyle("-fx-font-weight: bold;");
        name.setMinWidth(0);
        name.setTextOverrun(OverrunStyle.CENTER_ELLIPSIS);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox head = new HBox(6, icon, name, spacer);
        head.setAlignment(Pos.CENTER_LEFT);
        // Rich header: commit count, last author, push state and quick actions in one row.
        if (!branch.commits().isEmpty()) {
            head.getChildren().add(pill(String.valueOf(branch.commits().size()), "gray"));
            String lastAuthor = branch.commits().get(0).authorName();
            if (lastAuthor != null && !lastAuthor.isBlank()) {
                Label by = muted(lastAuthor);
                by.setMaxWidth(110);
                by.setTextOverrun(OverrunStyle.ELLIPSIS);
                head.getChildren().add(by);
            }
        }
        Label state = statusPill(branch.status());
        if (state != null) head.getChildren().add(state);
        if (branch.name() != null && !branch.name().isBlank() && needsPush(branch.status())) {
            Button push = new Button(null, new FontIcon("mdi2a-arrow-up"));
            push.setTooltip(new Tooltip("Push " + branch.name()));
            push.disableProperty().bind(vm.actionBusyProperty());
            push.setOnAction(e -> vm.pushBranch(branch.name()));
            head.getChildren().add(push);
        }
        head.getChildren().add(actionsButton(branchMenuItems(branch, vm)));
        card.getChildren().add(head);
        if (landed) {
            Label hint = muted("Already merged upstream: use Pull remote to clear it. Its commits cannot be changed.");
            hint.setWrapText(true);
            card.getChildren().add(hint);
        }

        boolean named = branch.name() != null && !branch.name().isBlank() && !landed;
        if (named) {
            // Drag the header to restack the branch; drop commits or changed files on the card.
            head.setOnDragDetected(e -> {
                if (e.getButton() != MouseButton.PRIMARY) return;
                draggingBranch.set(true);
                ButDnd.start(head, e, new ButDnd.Payload(ButDnd.Kind.BRANCH, List.of(branch.name())));
            });
            head.setOnDragDone(e -> {
                draggingBranch.set(false);
                ButDnd.finish();
            });
            card.setOnDragOver(e -> {
                ButDnd.Payload p = ButDnd.current();
                if (p == null || (p.kind() == ButDnd.Kind.BRANCH && p.ids().contains(branch.name()))) return;
                if (ButDnd.accept(e, ButDnd.Kind.COMMIT, ButDnd.Kind.FILE, ButDnd.Kind.BRANCH)) {
                    ButDnd.mark(card, color, ButDnd.Side.ALL);
                }
            });
            card.setOnDragExited(e -> ButDnd.unmark(card));
            card.setOnDragDropped(e -> {
                ButDnd.Payload p = ButDnd.current();
                ButDnd.unmark(card);
                if (p == null) return;
                switch (p.kind()) {
                    case COMMIT -> {
                        // Dropping a commit on the branch it already belongs to would be a no-op (and but refuses it).
                        if (branch.name().equals(p.source())) return;
                        vm.moveCommitsToBranch(p.ids(), branch.name());
                    }
                    case FILE -> vm.repo().composeCommit(p.ids(), branch.name());
                    case BRANCH -> {
                        if (p.ids().contains(branch.name())) return;
                        vm.moveBranchAbove(p.ids().get(0), branch.name());
                    }
                }
                e.setDropCompleted(true);
                e.consume();
            });
        }

        if (branch.commits().isEmpty()) {
            card.getChildren().add(muted("No commits on this branch yet."));
        } else {
            VBox rows = new VBox(0);
            for (ButStatus.Commit commit : branch.commits()) rows.getChildren().add(commitRow(commit, false, landed, vm, branch.name(), color));
            card.getChildren().add(rows);
        }
        if (!branch.upstreamCommits().isEmpty()) {
            Label upstream = new Label("Not yet integrated");
            upstream.setStyle("-fx-font-size: 10px; -fx-text-fill: #d29922;");
            VBox rows = new VBox(0);
            for (ButStatus.Commit commit : branch.upstreamCommits()) {
                rows.getChildren().add(commitRow(commit, true, landed, vm, branch.name(), color));
            }
            card.getChildren().addAll(upstream, rows);
        }

        attachMenu(card, new ContextMenu(branchMenuItems(branch, vm).toArray(MenuItem[]::new)));
        return card;
    }

    private List<MenuItem> branchMenuItems(ButStatus.Branch branch, GitButlerViewModel vm) {
        List<MenuItem> items = new ArrayList<>();
        MenuItem copyName = item("Copy branch name", () -> copy(branch.name()));
        copyName.setDisable(branch.name() == null || branch.name().isBlank());
        items.add(copyName);
        if (branch.cliId() != null && !branch.cliId().isBlank()) {
            items.add(item("Copy branch CLI ID", () -> copy(branch.cliId())));
        }
        MenuItem stack = item("New stacked branch…", () -> showNewBranchBar(branch.name()));
        MenuItem rename = item("Rename branch…", () -> openRenameBranchDialog(vm, branch));
        MenuItem move = item("Move above…", () -> openMoveBranchDialog(vm, branch));
        if (branch.name() == null || branch.name().isBlank()) {
            stack.setDisable(true);
            rename.setDisable(true);
            move.setDisable(true);
        } else {
            stack.disableProperty().bind(vm.actionBusyProperty());
            rename.disableProperty().bind(vm.actionBusyProperty());
            move.disableProperty().bind(vm.actionBusyProperty());
        }
        items.add(stack);
        items.add(rename);
        items.add(move);
        MenuItem push = item("Push branch / stack", () -> vm.pushBranch(branch.name()));
        MenuItem update = item("Update from remote", () -> confirmUpdate(vm, branch.name()));
        MenuItem unapply = item("Unapply from workspace", () -> confirmUnapply(vm, branch.name()));
        if (branch.name() == null || branch.name().isBlank()) {
            push.setDisable(true);
            update.setDisable(true);
            unapply.setDisable(true);
        } else {
            if (needsPush(branch.status())) push.disableProperty().bind(vm.actionBusyProperty());
            else push.setDisable(true);
            update.disableProperty().bind(vm.actionBusyProperty());
            unapply.disableProperty().bind(vm.actionBusyProperty());
        }
        items.add(new SeparatorMenuItem());
        items.addAll(List.of(push, update, new SeparatorMenuItem(), unapply));
        return items;
    }

    private VBox unappliedBranchesCard(List<ButBranch> branches, GitButlerViewModel vm) {
        VBox card = card();
        Label count = pill(branchFilterSummary(branches.size(), branches.size()), "gray");
        HBox header = cardHeader("Branches not in workspace", "apply a branch to work on it");
        header.getChildren().add(count);
        card.getChildren().add(header);

        TextField filter = new TextField(unappliedFilter);
        filter.setPromptText("Filter by name or author…");
        filter.setTooltip(new Tooltip("Matches the branch name or the author of its last commit. "
                + "Ctrl+F focuses this field; Esc clears it."));
        filter.setPrefColumnCount(18);
        filter.setMaxWidth(300);
        filter.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE && !filter.getText().isEmpty()) {
                filter.clear();
                e.consume();
            }
        });
        Button clear = new Button("Clear");
        clear.setDisable(unappliedFilter.isBlank());
        clear.setOnAction(e -> {
            filter.clear();
            filter.requestFocus();
        });
        branchFilterField = filter;
        HBox filterBar = new HBox(8, filter, clear);
        filterBar.setAlignment(Pos.CENTER_LEFT);
        card.getChildren().add(filterBar);

        VBox rows = new VBox(4);
        Runnable refresh = () -> {
            String query = filter.getText() == null ? "" : filter.getText().strip();
            unappliedFilter = query;
            List<Node> visible = new ArrayList<>();
            int matched = 0;
            for (ButBranch branch : branches) {
                if (!matchesBranch(branch, query)) continue;
                visible.add(unappliedBranchRow(branch, vm, query));
                matched++;
            }
            if (visible.isEmpty()) {
                visible.add(muted(query.isEmpty()
                        ? "No unapplied branches."
                        : "No branches match \"" + query + "\"."));
            }
            rows.getChildren().setAll(visible);
            count.setText(branchFilterSummary(matched, branches.size()));
            clear.setDisable(query.isEmpty());
        };
        filter.textProperty().addListener((o, a, b) -> refresh.run());
        refresh.run();
        card.getChildren().add(rows);
        return card;
    }

    /** Focuses the branch filter, selecting its text, and scrolls it into view when needed. */
    public void focusBranchFilter() {
        TextField filter = branchFilterField;
        if (filter == null) {
            showBranchFilterHint("No branch filter to focus: there are no unapplied branches.");
            return;
        }
        if (filter.getScene() == null) return;
        filter.requestFocus();
        filter.selectAll();
        Platform.runLater(() -> reveal(filter));
    }

    private void showBranchFilterHint(String text) {
        branchFilterHint.setText(text);
        branchFilterHint.setVisible(true);
        branchFilterHint.setManaged(true);
        branchFilterHintTimer.playFromStart();
    }

    private void hideBranchFilterHint() {
        branchFilterHint.setVisible(false);
        branchFilterHint.setManaged(false);
    }

    private void reveal(Node node) {
        Bounds nodeBounds = node.localToScene(node.getBoundsInLocal());
        Bounds viewport = scroll.localToScene(scroll.getViewportBounds());
        if (viewport.getHeight() <= 0) return;
        if (nodeBounds.getMinY() >= viewport.getMinY()
                && nodeBounds.getMaxY() <= viewport.getMaxY()) return;
        double contentHeight = content.getBoundsInLocal().getHeight();
        double scrollable = contentHeight - viewport.getHeight();
        if (scrollable <= 0) return;
        Bounds contentBounds = content.localToScene(content.getBoundsInLocal());
        double y = nodeBounds.getMinY() - contentBounds.getMinY();
        double target = (y - (viewport.getHeight() - nodeBounds.getHeight()) / 2) / scrollable;
        scroll.setVvalue(Math.max(0, Math.min(1, target)));
    }

    private static boolean matchesBranch(ButBranch branch, String query) {
        if (query.isEmpty()) return true;
        String needle = query.toLowerCase(Locale.ROOT);
        return matches(branch.name(), needle) || matches(branch.lastAuthor(), needle);
    }

    private static boolean matches(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    private static String branchFilterSummary(int visible, int total) {
        if (visible == total) return count(total, "branch", "branches");
        return visible + " of " + count(total, "branch", "branches");
    }

    private static HBox unappliedBranchRow(ButBranch branch, GitButlerViewModel vm, String query) {
        FontIcon icon = new FontIcon("mdi2s-source-branch");
        icon.setIconSize(13);
        icon.setIconColor(javafx.scene.paint.Color.web("#8b949e"));
        String nameText = branch.name() == null || branch.name().isBlank() ? "(unnamed branch)" : branch.name();
        String needle = query == null ? "" : query.toLowerCase(Locale.ROOT);
        boolean nameMatch = !needle.isEmpty() && matches(branch.name(), needle);
        boolean authorMatch = !needle.isEmpty() && matches(branch.lastAuthor(), needle);
        Node name;
        if (nameMatch) {
            name = highlightedText(nameText, query, NAME_TEXT);
        } else {
            Label label = new Label(nameText);
            label.setStyle("-fx-font-weight: bold;");
            label.setMaxWidth(Double.MAX_VALUE);
            label.setTextOverrun(OverrunStyle.CENTER_ELLIPSIS);
            name = label;
        }
        Node meta = authorMatch ? branchMetaFlow(branch, query) : muted(branchMeta(branch));
        VBox labels = new VBox(1, name, meta);
        labels.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(labels, Priority.ALWAYS);
        Button apply = new Button("Apply");
        apply.setOnAction(e -> vm.applyBranch(branch.name()));
        if (branch.name() == null || branch.name().isBlank()) apply.setDisable(true);
        else apply.disableProperty().bind(vm.actionBusyProperty());
        HBox row = new HBox(8, icon, labels);
        if (nameMatch) {
            Label badge = pill("name match", "blue");
            badge.setTooltip(new Tooltip("Filter matched the branch name"));
            row.getChildren().add(badge);
        }
        if (authorMatch) {
            Label badge = pill("author match", "amber");
            badge.setTooltip(new Tooltip("Filter matched the last author: " + branch.lastAuthor()));
            row.getChildren().add(badge);
        }
        row.getChildren().add(apply);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(0));

        MenuItem applyItem = item("Apply branch", () -> vm.applyBranch(branch.name()));
        if (branch.name() == null || branch.name().isBlank()) applyItem.setDisable(true);
        else applyItem.disableProperty().bind(vm.actionBusyProperty());
        attachMenu(row, new ContextMenu(applyItem,
                item("Copy branch name", () -> copy(branch.name()))));
        return row;
    }

    private static String branchMeta(ButBranch branch) {
        StringBuilder text = new StringBuilder();
        if (branch.commitsAhead() != null) {
            text.append(branch.commitsAhead() == 0 ? "up to date"
                    : branch.commitsAhead() + (branch.commitsAhead() == 1 ? " commit ahead" : " commits ahead"));
        }
        if (branch.lastCommitAt() != null) appendMeta(text, DateFormats.dateTime(branch.lastCommitAt()));
        if (branch.lastAuthor() != null && !branch.lastAuthor().isBlank()) appendMeta(text, branch.lastAuthor());
        if (!branch.hasLocal()) appendMeta(text, "remote only");
        if (!branch.mergesCleanly()) appendMeta(text, "may conflict");
        return text.toString();
    }

    /** Same metadata as {@link #branchMeta} with the matched author substring highlighted. */
    private static TextFlow branchMetaFlow(ButBranch branch, String query) {
        TextFlow flow = new TextFlow();
        flow.setMaxWidth(Double.MAX_VALUE);
        if (branch.commitsAhead() != null) {
            addMetaPart(flow, branch.commitsAhead() == 0 ? "up to date"
                    : branch.commitsAhead() + (branch.commitsAhead() == 1 ? " commit ahead" : " commits ahead"), null);
        }
        if (branch.lastCommitAt() != null) addMetaPart(flow, DateFormats.dateTime(branch.lastCommitAt()), null);
        if (branch.lastAuthor() != null && !branch.lastAuthor().isBlank()) {
            addMetaPart(flow, branch.lastAuthor(), query);
        }
        if (!branch.hasLocal()) addMetaPart(flow, "remote only", null);
        if (!branch.mergesCleanly()) addMetaPart(flow, "may conflict", null);
        return flow;
    }

    private static void addMetaPart(TextFlow flow, String value, String query) {
        if (!flow.getChildren().isEmpty()) {
            Text separator = new Text(" · ");
            separator.setStyle(META_TEXT);
            flow.getChildren().add(separator);
        }
        if (query == null || query.isBlank()) flow.getChildren().add(styledText(value, META_TEXT));
        else flow.getChildren().addAll(highlightedTexts(value, query, META_TEXT));
    }

    private static TextFlow highlightedText(String text, String query, String baseStyle) {
        TextFlow flow = new TextFlow();
        flow.setMaxWidth(Double.MAX_VALUE);
        flow.getChildren().addAll(highlightedTexts(text, query, baseStyle));
        return flow;
    }

    /** Splits text into normal and matched runs so only the exact substring is highlighted. */
    private static List<Text> highlightedTexts(String text, String query, String baseStyle) {
        List<Text> parts = new ArrayList<>();
        if (text == null || text.isEmpty() || query == null || query.isBlank()) {
            parts.add(styledText(text == null ? "" : text, baseStyle));
            return parts;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        String needle = query.toLowerCase(Locale.ROOT);
        int start = 0;
        while (true) {
            int at = lower.indexOf(needle, start);
            if (at < 0) {
                if (start < text.length()) parts.add(styledText(text.substring(start), baseStyle));
                break;
            }
            if (at > start) parts.add(styledText(text.substring(start, at), baseStyle));
            Text match = new Text(text.substring(at, at + needle.length()));
            match.setStyle(baseStyle + " -fx-fill: #e3b341; -fx-font-weight: bold;"
                    + " -fx-underline: true;");
            parts.add(match);
            start = at + needle.length();
        }
        return parts;
    }

    private static Text styledText(String text, String style) {
        Text node = new Text(text);
        node.setStyle(style);
        return node;
    }

    private static void appendMeta(StringBuilder text, String value) {
        if (!text.isEmpty()) text.append(" · ");
        text.append(value);
    }

    private HBox commitRow(ButStatus.Commit commit, boolean upstream, boolean landed, GitButlerViewModel vm,
            String sourceBranch, String color) {
        Node marker;
        if (upstream) {
            Label up = new Label("↑");
            up.setMinWidth(12);
            up.setAlignment(Pos.CENTER);
            up.setStyle("-fx-font-size: 10px; -fx-text-fill: #d29922;");
            marker = up;
        } else {
            // A vertical line in the stack colour with a dot per commit, so the commits of a branch read as a chain.
            javafx.scene.layout.Region line = new javafx.scene.layout.Region();
            line.setMinWidth(2);
            line.setPrefWidth(2);
            line.setMaxWidth(2);
            line.setMaxHeight(Double.MAX_VALUE);
            line.setStyle("-fx-background-color: " + color + "; -fx-opacity: 0.55;");
            javafx.scene.shape.Circle dot = new javafx.scene.shape.Circle(4, javafx.scene.paint.Color.web(color));
            dot.setStroke(javafx.scene.paint.Color.web("#0d1117"));
            dot.setStrokeWidth(1.5);
            javafx.scene.layout.StackPane rail = new javafx.scene.layout.StackPane(line, dot);
            rail.setMinWidth(12);
            rail.setPrefWidth(12);
            marker = rail;
        }

        Label hash = new Label(commit.shortId());
        hash.setMinWidth(58);
        hash.setStyle("-fx-font-family: 'Consolas', monospace; -fx-font-size: 11px; -fx-text-fill: #79c0ff;");

        Label subject = new Label(commit.subject().isBlank() ? "(no message)" : commit.subject());
        subject.setMaxWidth(Double.MAX_VALUE);
        subject.setMinWidth(0);
        subject.setTextOverrun(OverrunStyle.CENTER_ELLIPSIS);
        HBox.setHgrow(subject, Priority.ALWAYS);

        Label date = new Label(commit.createdAt() == null ? "" : DateFormats.dateTime(commit.createdAt()));
        date.setStyle("-fx-font-size: 10px; -fx-opacity: 0.6;");

        HBox row = new HBox(8, marker, hash);
        if (commit.cliId() != null && !commit.cliId().isBlank()) row.getChildren().add(cliId(commit.cliId()));
        row.getChildren().add(subject);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(1, 4, 1, 0));
        row.getStyleClass().add("bt-commit");
        row.setUserData(commit);
        if (commit.conflicted()) row.getChildren().add(pill("conflict", "red"));
        row.getChildren().add(date);

        List<MenuItem> menuItems = commitMenuItems(commit, upstream, vm, landed ? null : sourceBranch);
        if (!menuItems.isEmpty()) {
            attachMenu(row, new ContextMenu(menuItems.toArray(MenuItem[]::new)));
            if (!upstream && !landed && sourceBranch != null && !sourceBranch.isBlank()) {
                row.getChildren().add(actionsButton(commitMenuItems(commit, upstream, vm, sourceBranch)));
            }
        }

        String id = commit.cliId() == null || commit.cliId().isBlank() ? null : commit.cliId();
        boolean draggable = !upstream && !landed && id != null && sourceBranch != null && !sourceBranch.isBlank();

        row.setOnMouseClicked(e -> {
            if (e.getButton() != MouseButton.PRIMARY || e.getClickCount() != 1 || upstream) return;
            if (id != null && (e.isControlDown() || e.isShortcutDown())) {
                if (!selectedCommits.remove(id)) selectedCommits.add(id);
            } else if (id != null) {
                selectedCommits.clear();
                selectedCommits.add(id);
                if (commit.id() != null && !commit.id().isBlank()) {
                    vm.repo().diff().showCommitByHash(commit.id(), commit.subject());
                }
            }
            refreshRowStyles();
        });

        if (draggable) {
            row.setOnDragDetected(e -> {
                if (e.getButton() != MouseButton.PRIMARY) return;
                if (!selectedCommits.contains(id)) {
                    selectedCommits.clear();
                    selectedCommits.add(id);
                    refreshRowStyles();
                }
                ButDnd.start(row, e, new ButDnd.Payload(ButDnd.Kind.COMMIT, List.copyOf(selectedCommits),
                        selectedCommits.size() == 1 ? sourceBranch : null));
            });
            row.setOnDragDone(e -> ButDnd.finish());
            row.setOnDragOver(e -> {
                ButDnd.Payload p = ButDnd.current();
                if (p == null || p.ids().contains(id)) return;
                if (ButDnd.accept(e, ButDnd.Kind.COMMIT)) {
                    ButDnd.mark(row, color, e.getY() < row.getHeight() / 2 ? ButDnd.Side.TOP : ButDnd.Side.BOTTOM);
                } else if (ButDnd.accept(e, ButDnd.Kind.FILE)) {
                    ButDnd.mark(row, color, ButDnd.Side.ALL);
                }
            });
            row.setOnDragExited(e -> ButDnd.unmark(row));
            row.setOnDragDropped(e -> {
                ButDnd.Payload p = ButDnd.current();
                ButDnd.unmark(row);
                if (p == null || p.ids().contains(id)) return;
                if (p.kind() == ButDnd.Kind.COMMIT) {
                    vm.moveCommits(p.ids(), id, e.getY() < row.getHeight() / 2);
                } else if (p.kind() == ButDnd.Kind.FILE) {
                    vm.amendChanges(p.ids(), id);
                } else {
                    return;
                }
                e.setDropCompleted(true);
                e.consume();
            });
        }
        String full = commit.id() == null || commit.id().isBlank() ? commit.shortId() : commit.id();
        String author = commit.authorName() == null || commit.authorName().isBlank() ? "" : "\n" + commit.authorName();
        String when = commit.createdAt() == null ? "" : "\n" + DateFormats.dateTimeSeconds(commit.createdAt());
        Tooltip.install(row, new Tooltip(commit.message().isBlank() ? full : full + "\n" + commit.message().strip()
                + author + when));
        return row;
    }

    private static List<MenuItem> changeMenuItems(ButStatus.Change change, GitButlerViewModel vm,
            String changePath) {
        List<MenuItem> items = new ArrayList<>();
        MenuItem commit = item("Commit this change…", () -> vm.repo().composeCommit(List.of(changePath), null));
        MenuItem discard = item("Discard change…", () -> confirmDiscard(vm, change));
        if (changePath == null || changePath.isBlank()) {
            commit.setDisable(true);
            discard.setDisable(true);
        } else {
            commit.disableProperty().bind(vm.actionBusyProperty());
            discard.disableProperty().bind(vm.actionBusyProperty());
        }
        items.add(commit);
        items.add(discard);

        boolean deleted = "deleted".equalsIgnoreCase(change.changeType());
        MenuItem open = item("Open file", () -> vm.openFile(change.path()));
        open.setDisable(change.path() == null || change.path().isBlank() || deleted);
        MenuItem copyPath = item("Copy path", () -> copy(change.path()));
        copyPath.setDisable(change.path() == null || change.path().isBlank());
        MenuItem copyAbsolutePath = item("Copy absolute path",
                () -> copy(vm.absolutePath(change.path())));
        copyAbsolutePath.setDisable(change.path() == null || change.path().isBlank());
        MenuItem copy = item("Copy CLI ID", () -> copy(change.cliId()));
        copy.setDisable(change.cliId() == null || change.cliId().isBlank());
        items.add(new SeparatorMenuItem());
        items.add(open);
        items.add(new SeparatorMenuItem());
        items.addAll(List.of(copyPath, copyAbsolutePath, copy));
        return items;
    }

    private List<MenuItem> commitMenuItems(ButStatus.Commit commit, boolean upstream,
            GitButlerViewModel vm, String sourceBranch) {
        List<MenuItem> items = new ArrayList<>();
        if (commit.id() != null && !commit.id().isBlank()) {
            items.add(item("Copy commit hash", () -> copy(commit.id())));
        }
        if (commit.cliId() != null && !commit.cliId().isBlank()) {
            items.add(item("Copy commit CLI ID", () -> copy(commit.cliId())));
        }
        String target = commit.cliId() != null && !commit.cliId().isBlank() ? commit.cliId() : commit.id();
        if (!upstream && sourceBranch != null && !sourceBranch.isBlank()
                && target != null && !target.isBlank()) {
            MenuItem reword = item("Edit commit message…", () -> openRewordCommitDialog(vm, commit, target));
            MenuItem squash = item("Squash selected commits into this one…", () -> {
                List<String> picked = List.copyOf(selectedCommits);
                if (picked.isEmpty() || picked.contains(target)) {
                    new Alert(Alert.AlertType.INFORMATION,
                            "Select other commits first (Ctrl+click), then squash them into this one.", ButtonType.OK).show();
                } else if (Dialogs.confirm("Squash commits", "Squash " + picked.size() + " commit(s) into "
                        + commit.shortId() + "?\n\nTheir messages are discarded; you can undo it with but undo.")) {
                    selectedCommits.clear();
                    vm.squashCommits(picked, target);
                }
            });
            squash.disableProperty().bind(vm.actionBusyProperty());
            items.add(squash);
            MenuItem move = item("Move to branch…", () -> openMoveCommitDialog(vm, commit, target, sourceBranch));
            reword.disableProperty().bind(vm.actionBusyProperty());
            move.disableProperty().bind(vm.actionBusyProperty());
            items.add(new SeparatorMenuItem());
            items.addAll(List.of(reword, move));
            if (commit.cliId() != null && !commit.cliId().isBlank()) {
                MenuItem uncommit = item("Move to uncommitted", () -> confirmUncommit(vm, commit));
                uncommit.disableProperty().bind(vm.actionBusyProperty());
                items.add(uncommit);
            }
        }
        return items;
    }

    private static void openMoveCommitDialog(GitButlerViewModel vm, ButStatus.Commit commit, String target,
            String sourceBranch) {
        ButStatus status = vm.statusProperty().get();
        if (status == null) return;
        List<AppliedBranch> branches = appliedBranches(status).stream()
                .filter(candidate -> !candidate.name().equals(sourceBranch))
                .toList();
        if (branches.isEmpty()) {
            Alert alert = new Alert(Alert.AlertType.INFORMATION, null, ButtonType.OK);
            alert.setTitle("Move commit");
            alert.setHeaderText("No other applied branches");
            alert.setContentText("Apply another branch to the workspace before moving this commit.");
            alert.show();
            return;
        }

        ComboBox<AppliedBranch> branch = new ComboBox<>(FXCollections.observableArrayList(branches));
        branch.setValue(branches.get(0));
        branch.setMaxWidth(Double.MAX_VALUE);
        Label hint = muted("The commit is moved to the tip of the selected branch. Dependent commits are restacked.");
        hint.setWrapText(true);
        VBox form = new VBox(8, new Label("Destination branch"), branch, hint);
        form.setPadding(new Insets(6, 0, 0, 0));

        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle("Move commit");
        dialog.setHeaderText("Move commit " + commit.shortId());
        dialog.getDialogPane().setContent(form);
        dialog.getDialogPane().setPrefWidth(440);
        ButtonType moveType = new ButtonType("Move commit", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(moveType, ButtonType.CANCEL);
        Node moveButton = dialog.getDialogPane().lookupButton(moveType);
        moveButton.disableProperty().bind(branch.valueProperty().isNull());
        dialog.setResultConverter(button -> button == moveType ? branch.getValue().name() : null);
        dialog.showAndWait().ifPresent(destination -> {
            int from = stackNumber(status, sourceBranch);
            int to = stackNumber(status, destination);
            if (from > 0 && to > 0 && from != to) {
                confirmMoveCommitToStack(vm, target, destination, sourceBranch, from, to);
            } else {
                vm.moveCommitToBranch(target, destination);
            }
        });
    }

    private static void confirmMoveCommitToStack(GitButlerViewModel vm, String commit, String destination,
            String source, int fromStack, int toStack) {
        ButtonType moveType = new ButtonType("Move commit", ButtonBar.ButtonData.OK_DONE);
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, null, moveType, ButtonType.CANCEL);
        alert.setTitle("Move commit to another stack");
        alert.setHeaderText("Move this commit from stack " + fromStack + " to stack " + toStack + "?");
        alert.setContentText("The commit leaves '" + source + "' in stack " + fromStack
                + " and is added to '" + destination + "' in stack " + toStack
                + ". Dependent commits are restacked.");
        defaultToCancel(alert);
        alert.showAndWait().filter(moveType::equals)
                .ifPresent(button -> vm.moveCommitToBranch(commit, destination));
    }

    private static void openRewordCommitDialog(GitButlerViewModel vm, ButStatus.Commit commit, String target) {
        String initial = commit.message() == null || commit.message().isBlank()
                ? commit.subject()
                : commit.message().strip();
        TextArea message = new TextArea(initial);
        message.setWrapText(true);
        message.setPrefRowCount(6);
        Platform.runLater(message::selectAll);
        Label hint = muted("This recreates the commit and restacks any commits that depend on it.");
        hint.setWrapText(true);
        VBox form = new VBox(8, new Label("Commit message"), message, hint);
        form.setPadding(new Insets(6, 0, 0, 0));

        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle("Edit commit message");
        dialog.setHeaderText("Edit commit " + commit.shortId());
        dialog.getDialogPane().setContent(form);
        dialog.getDialogPane().setPrefWidth(480);
        ButtonType saveType = new ButtonType("Save message", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveType, ButtonType.CANCEL);
        Node saveButton = dialog.getDialogPane().lookupButton(saveType);
        saveButton.disableProperty().bind(Bindings.createBooleanBinding(
                () -> message.getText() == null || message.getText().isBlank()
                        || message.getText().strip().equals(initial),
                message.textProperty()));
        dialog.setResultConverter(button -> button == saveType ? message.getText().strip() : null);
        dialog.showAndWait().ifPresent(text -> vm.rewordCommit(target, text));
    }

    private static void openMoveBranchDialog(GitButlerViewModel vm, ButStatus.Branch branch) {
        if (branch.name() == null || branch.name().isBlank()) return;
        ButStatus status = vm.statusProperty().get();
        if (status == null) return;
        List<String> targets = status.stacks().stream()
                .filter(stack -> stack.branches().stream()
                        .anyMatch(candidate -> branch.name().equals(candidate.name())))
                .findFirst()
                .map(ButStatus.Stack::branches)
                .orElse(List.of())
                .stream()
                .map(ButStatus.Branch::name)
                .filter(name -> name != null && !name.isBlank() && !name.equals(branch.name()))
                .distinct()
                .toList();
        if (targets.isEmpty()) {
            Alert alert = new Alert(Alert.AlertType.INFORMATION, null, ButtonType.OK);
            alert.setTitle("Move branch");
            alert.setHeaderText("No other branches in this stack");
            alert.setContentText("Add another branch to this stack before moving this branch.");
            alert.show();
            return;
        }

        ComboBox<String> target = new ComboBox<>(FXCollections.observableArrayList(targets));
        target.setValue(targets.get(0));
        target.setMaxWidth(Double.MAX_VALUE);
        Label hint = muted("The branch keeps its commits and is moved directly above the selected branch.");
        hint.setWrapText(true);
        VBox form = new VBox(8, new Label("Move above"), target, hint);
        form.setPadding(new Insets(6, 0, 0, 0));

        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle("Move branch");
        dialog.setHeaderText("Move '" + branch.name() + "' in the stack");
        dialog.getDialogPane().setContent(form);
        dialog.getDialogPane().setPrefWidth(440);
        ButtonType moveType = new ButtonType("Move branch", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(moveType, ButtonType.CANCEL);
        Node moveButton = dialog.getDialogPane().lookupButton(moveType);
        moveButton.disableProperty().bind(target.valueProperty().isNull());
        dialog.setResultConverter(button -> button == moveType ? target.getValue() : null);
        dialog.showAndWait().ifPresent(above -> vm.moveBranchAbove(branch.name(), above));
    }

    private static void openRenameBranchDialog(GitButlerViewModel vm, ButStatus.Branch branch) {
        if (branch.name() == null || branch.name().isBlank()) return;
        if (branch.commits().isEmpty()) {
            Alert alert = new Alert(Alert.AlertType.INFORMATION, null, ButtonType.OK);
            alert.setTitle("Rename branch");
            alert.setHeaderText("Rename unavailable");
            alert.setContentText("Create at least one commit on this branch before renaming it.");
            alert.show();
            return;
        }
        String current = branch.name();
        TextField name = new TextField(current);
        name.setPromptText("branch-name");
        Platform.runLater(name::selectAll);
        Label hint = muted("The branch keeps its commits and its position in the stack.");
        hint.setWrapText(true);
        VBox form = new VBox(8, new Label("Branch name"), name, hint);
        form.setPadding(new Insets(6, 0, 0, 0));

        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle("Rename branch");
        dialog.setHeaderText("Rename '" + current + "'");
        dialog.getDialogPane().setContent(form);
        dialog.getDialogPane().setPrefWidth(440);
        ButtonType renameType = new ButtonType("Rename", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(renameType, ButtonType.CANCEL);
        Node renameButton = dialog.getDialogPane().lookupButton(renameType);
        renameButton.disableProperty().bind(Bindings.createBooleanBinding(
                () -> {
                    String value = name.getText() == null ? "" : name.getText().trim();
                    return value.isEmpty() || value.startsWith("-") || value.equals(current);
                }, name.textProperty()));
        dialog.setResultConverter(button -> button == renameType ? name.getText().trim() : null);
        dialog.showAndWait().ifPresent(newName -> vm.renameBranch(current, newName));
    }

    /** Inline (non-modal) bar to create a branch above or below a reference branch. */
    private VBox buildNewBranchBar(GitButlerViewModel vm) {
        newBranchName = new TextField();
        newBranchName.setPromptText("feature/my-branch");
        newBranchName.setPrefWidth(260);

        newBranchAbove = new RadioButton("Above");
        newBranchAbove.setSelected(true);
        newBranchBelow = new RadioButton("Below");
        ToggleGroup position = new ToggleGroup();
        newBranchAbove.setToggleGroup(position);
        newBranchBelow.setToggleGroup(position);
        HBox positions = new HBox(14, new Label("Position:"), newBranchAbove, newBranchBelow);
        positions.setAlignment(Pos.CENTER_LEFT);
        positions.setVisible(false);
        positions.setManaged(false);

        newBranchHint = new Label("The branch is created applied to the workspace without a stack.");
        newBranchHint.setWrapText(true);
        newBranchHint.getStyleClass().add("muted");

        Button create = new Button("Create branch");
        create.getStyleClass().add("button");
        create.disableProperty().bind(Bindings.createBooleanBinding(
                () -> newBranchName.getText() == null || newBranchName.getText().isBlank()
                        || newBranchName.getText().trim().startsWith("-"),
                newBranchName.textProperty()));
        create.disableProperty().bind(Bindings.or(create.disableProperty(), vm.actionBusyProperty()));
        create.setOnAction(e -> commitNewBranch(vm));

        Button cancel = new Button("Cancel");
        cancel.getStyleClass().add("button");
        cancel.setOnAction(e -> hideNewBranchBar());

        HBox actions = new HBox(8, create, cancel);
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox bar = new VBox(6, new Label("Branch name"), newBranchName, positions, newBranchHint, actions);
        bar.setPadding(new Insets(6, 0, 0, 0));
        bar.setStyle("-fx-background-color: rgba(139,92,246,0.08); -fx-border-color: rgba(139,92,246,0.30);"
                + " -fx-border-radius: 6; -fx-border-width: 1; -fx-padding: 6 10 6 10;");
        bar.setAlignment(Pos.CENTER_LEFT);

        newBranchName.setOnKeyPressed(k -> {
            if (k.getCode() == KeyCode.ESCAPE) hideNewBranchBar();
        });

        return bar;
    }

    private void showNewBranchBar(String anchor) {
        newBranchAnchor = anchor;
        newBranchStacked = anchor != null && !anchor.isBlank();
        positions().setVisible(newBranchStacked);
        positions().setManaged(newBranchStacked);
        newBranchHint.setText(newBranchStacked
                ? "The new branch is created applied directly above or below '" + anchor + "'."
                : "The branch is created applied to the workspace without a stack.");
        newBranchName.setText("");
        newBranchName.requestFocus();
        newBranchBar.setVisible(true);
        newBranchBar.setManaged(true);
    }

    private HBox positions() {
        return (HBox) newBranchBar.getChildren().get(2);
    }

    private void hideNewBranchBar() {
        newBranchBar.setVisible(false);
        newBranchBar.setManaged(false);
    }

    private void commitNewBranch(GitButlerViewModel vm) {
        String name = newBranchName.getText().trim();
        if (name.isBlank() || name.startsWith("-")) return;
        hideNewBranchBar();
        if (!newBranchStacked) vm.createBranch(name);
        else if (newBranchAbove.isSelected()) vm.createBranchAbove(name, newBranchAnchor);
        else vm.createBranchBelow(name, newBranchAnchor);
    }

    private static void checkPull(GitButlerViewModel vm) {
        vm.checkPull(check -> showPullCheck(vm, check));
    }

    private static void showPullCheck(GitButlerViewModel vm, ButPullCheck check) {
        if (check == null) return;
        if (check.upToDate() && check.upstreamCommits() == 0 && !check.hasConflicts()) {
            Alert alert = new Alert(Alert.AlertType.INFORMATION, null, ButtonType.OK);
            alert.setTitle("Pull remote");
            alert.setHeaderText("Already up to date");
            alert.setContentText(check.summary());
            alert.show();
            return;
        }

        boolean conflicts = check.hasConflicts();
        ButtonType pullType = new ButtonType(conflicts ? "Pull anyway" : "Pull",
                ButtonBar.ButtonData.OK_DONE);
        Alert alert = new Alert(conflicts ? Alert.AlertType.WARNING : Alert.AlertType.CONFIRMATION,
                null, pullType, ButtonType.CANCEL);
        alert.setTitle("Pull remote");
        alert.setHeaderText(conflicts
                ? "Remote changes may conflict with the workspace"
                : "Ready to update the workspace");
        alert.setContentText(check.summary() + "\n\n" + (conflicts
                ? "Pulling now may leave conflicts to resolve. Dependent commits will be restacked. "
                        + "Afterwards use Resolve conflicts in the workspace to choose the remote or local side."
                : "Applied branches will be fetched and rebased onto the latest target. "
                        + "Local commits may be rewritten."));
        defaultToCancel(alert);
        alert.showAndWait().filter(pullType::equals).ifPresent(button -> vm.pullWorkspace());
    }

    private static VBox conflictCard(ButStatus status, GitButlerViewModel vm) {
        VBox card = card();
        card.setStyle(ALERT_CARD_STYLE);
        Label heading = new Label("Conflicted commits");
        heading.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;");
        HBox header = new HBox(8, heading,
                pill(count(status.conflictedCommits(), "commit", "commits"), "red"));
        header.setAlignment(Pos.CENTER_LEFT);
        Button resolve = new Button("Resolve conflicts…");
        resolve.setStyle("-fx-font-weight: bold;");
        resolve.setTooltip(new Tooltip("Choose the remote or local side for each hunk, or leave it unresolved"));
        resolve.disableProperty().bind(vm.actionBusyProperty());
        resolve.setOnAction(e -> openResolveDialog(vm));
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        header.getChildren().addAll(spacer, resolve);
        String next = nextConflict(status);
        Label detail = muted(count(status.conflictedCommits(),
                "commit is conflicted", "commits are conflicted")
                + (next.isBlank() ? "." : ". Next: " + next + ".")
                + " The resolver handles the oldest one; repeat for the rest.");
        detail.setWrapText(true);
        card.getChildren().addAll(header, detail);
        return card;
    }

    /** Branch and subject of the oldest conflicted commit, or an empty string when none is listed. */
    private static String nextConflict(ButStatus status) {
        ButStatus.Commit oldest = null;
        String where = "";
        for (ButStatus.Stack stack : status.stacks()) {
            for (ButStatus.Branch branch : stack.branches()) {
                for (ButStatus.Commit commit : branch.commits()) {
                    if (!commit.conflicted()) continue;
                    if (oldest != null && !isOlder(commit, oldest)) continue;
                    oldest = commit;
                    where = branch.name() == null || branch.name().isBlank()
                            ? "unnamed branch" : branch.name();
                }
            }
        }
        if (oldest == null) return "";
        String subject = oldest.subject().isBlank() ? "(no message)" : oldest.subject();
        String hash = oldest.shortId();
        return hash.isBlank() ? where + " · " + subject : where + " · " + hash + " " + subject;
    }

    private static boolean isOlder(ButStatus.Commit candidate, ButStatus.Commit current) {
        if (candidate.createdAt() == null) return false;
        if (current.createdAt() == null) return true;
        return candidate.createdAt().isBefore(current.createdAt());
    }

    private static void openResolveDialog(GitButlerViewModel vm) {
        vm.loadConflicts(conflicts -> showResolveDialog(vm, conflicts));
    }

    private static void showResolveDialog(GitButlerViewModel vm, ButConflicts conflicts) {
        if (conflicts == null) return;
        String target = conflicts.branch().isBlank() ? conflicts.commitId() : conflicts.branch();
        int hunkCount = conflicts.files().stream().mapToInt(file -> file.hunks().size()).sum();
        if (target.isBlank() || hunkCount == 0) {
            Alert alert = new Alert(Alert.AlertType.INFORMATION, null, ButtonType.OK);
            alert.setTitle("Resolve conflicts");
            alert.setHeaderText("No automatically applicable conflicts");
            alert.setContentText(conflicts.isEmpty()
                    ? "but did not return any conflict files."
                    : "No conflict hunk can be applied automatically."
                            + (conflicts.manual() > 0
                            ? " " + count(conflicts.manual(), "conflict requires", "conflicts require")
                                    + " manual resolution."
                            : ""));
            alert.show();
            return;
        }

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Resolve conflicts");
        dialog.setHeaderText("Resolve " + count(hunkCount, "conflict", "conflicts") + " in "
                + count(conflicts.files().size(), "file", "files") + " on " + target
                + " by choosing one side for each conflict.");
        VBox form = new VBox(14);
        Label note = muted("Remote side is the new base; local commit is the content of the branch. "
                + "Each hunk takes the chosen side. Leave unresolved keeps it for a later pass; "
                + "use the Git resolver for edits that combine both.");
        note.setWrapText(true);
        form.getChildren().add(note);
        List<HunkPick> picks = new ArrayList<>();
        for (ButConflicts.FileConflict file : conflicts.files()) {
            VBox row = new VBox(6);
            Label path = new Label(file.path().isBlank() ? "(unknown path)" : file.path());
            path.setStyle("-fx-font-family: 'Consolas', monospace; -fx-font-weight: bold;");
            row.getChildren().add(path);
            if (file.hunks().isEmpty()) {
                row.getChildren().add(muted("No automatic hunk for this file."));
            }
            for (int i = 0; i < file.hunks().size(); i++) {
                ButConflicts.Hunk hunk = file.hunks().get(i);
                Label heading = new Label("Conflict " + (i + 1)
                        + (hunk.line() > 0 ? " · line " + hunk.line() : ""));
                heading.setStyle("-fx-font-weight: bold; -fx-font-size: 11px;");
                Label preview = muted(hunk.preview());
                preview.setWrapText(true);
                ComboBox<String> side = new ComboBox<>(FXCollections.observableArrayList(
                        "Take remote side", "Keep local commit", "Leave unresolved"));
                side.getSelectionModel().selectFirst();
                side.setPrefWidth(190);
                VBox hunkBox = new VBox(3, heading, preview, side);
                hunkBox.setStyle("-fx-padding: 6 0 6 10; -fx-border-color: transparent transparent transparent rgba(110,118,129,0.35);");
                row.getChildren().add(hunkBox);
                if (!file.path().isBlank()) picks.add(new HunkPick(file.path(), i + 1, side));
            }
            form.getChildren().add(row);
        }
        Button allRemote = new Button("All remote");
        allRemote.setOnAction(e -> picks.forEach(pick -> pick.side().getSelectionModel().select(0)));
        Button allLocal = new Button("All local");
        allLocal.setOnAction(e -> picks.forEach(pick -> pick.side().getSelectionModel().select(1)));
        HBox bulk = new HBox(8, muted("Set every hunk:"), allRemote, allLocal);
        bulk.setAlignment(Pos.CENTER_LEFT);
        form.getChildren().add(1, bulk);
        if (conflicts.manual() > 0) {
            Label manual = new Label(count(conflicts.manual(), "conflict", "conflicts")
                    + " require manual resolution and are not included here.");
            manual.setWrapText(true);
            manual.setStyle("-fx-text-fill: #d29922;");
            form.getChildren().add(manual);
        }
        ScrollPane scroll = new ScrollPane(form);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(380);
        scroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        dialog.getDialogPane().setContent(scroll);
        dialog.getDialogPane().setPrefWidth(700);
        dialog.setResizable(true);

        ButtonType applyType = new ButtonType("Apply selected", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(applyType, ButtonType.CANCEL);
        Node apply = dialog.getDialogPane().lookupButton(applyType);
        Runnable updateApply = () -> apply.setDisable(picks.stream()
                .allMatch(pick -> pick.side().getSelectionModel().getSelectedIndex() == 2));
        for (HunkPick pick : picks) pick.side().valueProperty().addListener((o, a, b) -> updateApply.run());
        updateApply.run();
        dialog.showAndWait().filter(applyType::equals).ifPresent(button -> {
            List<ConflictChoice> choices = new ArrayList<>();
            for (HunkPick pick : picks) {
                int selected = pick.side().getSelectionModel().getSelectedIndex();
                if (selected == 2) continue;
                choices.add(new ConflictChoice(pick.path(), pick.conflict(), selected == 0));
            }
            vm.resolveConflicts(target, choices);
        });
    }

    private static void confirmPushStack(GitButlerViewModel vm, String branch, int branches) {
        if (branch == null || branch.isBlank()) return;
        ButtonType pushType = new ButtonType("Push stack", ButtonBar.ButtonData.OK_DONE);
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, null, pushType, ButtonType.CANCEL);
        alert.setTitle("Push stack");
        alert.setHeaderText(branches == 1
                ? "Push this stack branch to remote?"
                : "Push this stack of " + branches + " branches to remote?");
        alert.setContentText("All unpushed commits in the stack are pushed to its remote.");
        defaultToCancel(alert);
        alert.showAndWait().filter(pushType::equals).ifPresent(button -> vm.pushStack(branch));
    }

    private static void confirmUpdate(GitButlerViewModel vm, String branch) {
        if (branch == null || branch.isBlank()) return;
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, null, ButtonType.OK, ButtonType.CANCEL);
        alert.setTitle("Update branch");
        alert.setHeaderText("Update '" + branch + "' from its remote?");
        alert.setContentText("Local commits may be rebased onto the remote branch before continuing.");
        defaultToCancel(alert);
        alert.showAndWait().filter(ButtonType.OK::equals).ifPresent(button -> vm.updateBranch(branch));
    }

    private static void confirmUnapply(GitButlerViewModel vm, String branch) {
        if (branch == null || branch.isBlank()) return;
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, null, ButtonType.OK, ButtonType.CANCEL);
        alert.setTitle("Unapply branch");
        alert.setHeaderText("Remove '" + branch + "' and its stack from the workspace?");
        alert.setContentText("All branches in the stack are preserved and can be applied again later.");
        defaultToCancel(alert);
        alert.showAndWait().filter(ButtonType.OK::equals).ifPresent(button -> vm.unapplyBranch(branch));
    }

    /** Keeps Enter on Cancel so a confirmation cannot discard or rewrite work by accident. */
    private static void defaultToCancel(Alert alert) {
        for (ButtonType type : alert.getDialogPane().getButtonTypes()) {
            Node node = alert.getDialogPane().lookupButton(type);
            if (node instanceof Button button) {
                button.setDefaultButton(type.getButtonData() == ButtonBar.ButtonData.CANCEL_CLOSE);
            }
        }
    }

    private static boolean needsPush(String status) {
        if (status == null) return true;
        String value = status.toLowerCase(Locale.ROOT);
        return !(value.equals("completelypushed") || value.equals("pushed") || value.equals("integrated"));
    }

    private static void confirmDiscardSelected(GitButlerViewModel vm, List<String> paths) {
        if (paths == null || paths.isEmpty()) return;
        ButtonType discardType = new ButtonType("Discard", ButtonBar.ButtonData.OK_DONE);
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, null, discardType, ButtonType.CANCEL);
        alert.setTitle("Discard changes");
        alert.setHeaderText("Discard changes to " + paths.size()
                + (paths.size() == 1 ? " file?" : " files?"));
        alert.setContentText("The selected uncommitted changes are removed from the working tree. "
                + "Make sure you no longer need them.");
        defaultToCancel(alert);
        alert.showAndWait().filter(discardType::equals).ifPresent(button -> vm.discardChanges(paths));
    }

    private static void confirmDiscard(GitButlerViewModel vm, ButStatus.Change change) {
        if (change.path() == null || change.path().isBlank()) return;
        ButtonType discardType = new ButtonType("Discard", ButtonBar.ButtonData.OK_DONE);
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, null, discardType, ButtonType.CANCEL);
        alert.setTitle("Discard change");
        alert.setHeaderText("Discard changes to \"" + change.path() + "\"?");
        alert.setContentText("The uncommitted change is removed from the working tree. "
                + "Make sure you no longer need it.");
        defaultToCancel(alert);
        alert.showAndWait().filter(discardType::equals).ifPresent(button -> vm.discardChange(change.path()));
    }

    private static void confirmUncommit(GitButlerViewModel vm, ButStatus.Commit commit) {
        if (commit.cliId() == null || commit.cliId().isBlank()) return;
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, null, ButtonType.OK, ButtonType.CANCEL);
        alert.setTitle("Move commit to changes");
        alert.setHeaderText("Move " + commit.shortId() + " back to uncommitted changes?");
        alert.setContentText("The commit is removed from the branch; the branch itself is kept.");
        defaultToCancel(alert);
        alert.showAndWait().filter(ButtonType.OK::equals).ifPresent(button -> vm.uncommit(commit.cliId()));
    }

    private VBox baseCard(ButStatus.Commit base, GitButlerViewModel vm) {
        VBox card = card();
        card.getChildren().add(cardHeader("Base", "common base of the applied branches"));
        HBox row = commitRow(base, false, false, vm, null, "#6e7681");
        card.getChildren().add(row);
        return card;
    }

    private static TitledPane rawCard(String raw, boolean expanded) {
        TextArea area = new TextArea(raw);
        area.setEditable(false);
        area.setWrapText(false);
        area.setPrefRowCount(12);
        area.setStyle("-fx-font-family: 'Consolas', monospace; -fx-font-size: 11px;");
        TitledPane pane = new TitledPane("Raw JSON", area);
        pane.setExpanded(expanded);
        return pane;
    }

    private static VBox problemCard(String title, String message, String color) {
        VBox card = card();
        Label heading = new Label(title);
        heading.setStyle("-fx-font-weight: bold; -fx-text-fill: " + color + ";");
        Label text = new Label(message);
        text.setWrapText(true);
        text.setStyle("-fx-text-fill: " + color + ";");
        card.getChildren().addAll(heading, text);
        return card;
    }

    private static VBox card() {
        VBox card = new VBox(8);
        card.setStyle(CARD_STYLE);
        return card;
    }

    private static HBox cardHeader(String title, String subtitle) {
        Label heading = new Label(title);
        heading.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;");
        HBox header = new HBox(8, heading);
        header.setAlignment(Pos.CENTER_LEFT);
        if (subtitle != null && !subtitle.isBlank()) header.getChildren().add(muted(subtitle));
        return header;
    }

    private static Label cliId(String id) {
        Label label = new Label(id == null ? "" : id);
        label.setStyle("-fx-font-family: 'Consolas', monospace; -fx-font-size: 10px; -fx-opacity: 0.5;");
        if (id != null && !id.isBlank()) {
            label.setTooltip(new Tooltip("GitButler CLI ID: " + id + "\nDouble-click to copy."));
            label.setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                    copy(id);
                    e.consume();
                }
            });
            label.setContextMenu(new ContextMenu(item("Copy CLI ID", () -> copy(id))));
        }
        return label;
    }

    private record HunkPick(String path, int conflict, ComboBox<String> side) {}

    private record NewBranchRequest(String name, boolean above) {}

    private record AppliedBranch(String name, String label) {
        @Override public String toString() { return label; }
    }

    /** 1-based stack number for a branch, or 0 when the branch is not applied. */
    private static int stackNumber(ButStatus status, String branch) {
        if (status == null || branch == null || branch.isBlank()) return 0;
        int number = 0;
        for (ButStatus.Stack stack : status.stacks()) {
            number++;
            for (ButStatus.Branch candidate : stack.branches()) {
                if (branch.equals(candidate.name())) return number;
            }
        }
        return 0;
    }

    /** Applied branches labelled with their stack and position, in workspace order. */
    private static List<AppliedBranch> appliedBranches(ButStatus status) {
        List<AppliedBranch> result = new ArrayList<>();
        int stackNumber = 0;
        for (ButStatus.Stack stack : status.stacks()) {
            List<ButStatus.Branch> branches = stack.branches().stream()
                    .filter(branch -> branch.name() != null && !branch.name().isBlank())
                    .toList();
            if (branches.isEmpty()) continue;
            stackNumber++;
            for (int i = 0; i < branches.size(); i++) {
                String position = branches.size() == 1 ? "only branch"
                        : i == 0 ? "top"
                        : i == branches.size() - 1 ? "bottom"
                        : "middle";
                String label = branches.get(i).name() + "  ·  stack " + stackNumber + " · " + position;
                result.add(new AppliedBranch(branches.get(i).name(), label));
            }
        }
        return result;
    }

    private static void copy(String text) {
        if (text == null || text.isBlank()) return;
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
    }

    private static MenuItem item(String text, Runnable action) {
        MenuItem item = new MenuItem(text);
        item.setOnAction(e -> action.run());
        return item;
    }

    private static MenuButton actionsButton(List<MenuItem> items) {
        MenuButton button = new MenuButton("Actions");
        button.getItems().addAll(items);
        button.setFocusTraversable(false);
        button.setStyle("-fx-font-size: 11px;");
        return button;
    }

    private static void attachMenu(Node node, ContextMenu menu) {
        node.setOnContextMenuRequested(e -> {
            menu.show(node, e.getScreenX(), e.getScreenY());
            e.consume();
        });
    }

    private static Label muted(String text) {
        Label label = new Label(text);
        label.setStyle(MUTED);
        return label;
    }

    private static Label statusPill(String status) {
        String text = statusText(status);
        if (text.isBlank()) return null;
        return pill(text, statusTone(status));
    }

    private static String statusText(String status) {
        if (status == null || status.isBlank()) return "";
        return switch (status) {
            case "completelyUnpushed" -> "Unpushed";
            case "partiallyPushed" -> "Partially pushed";
            case "completelyPushed", "pushed" -> "Pushed";
            case "integrated" -> "Integrated";
            default -> {
                String words = status.replaceAll("([a-z0-9])([A-Z])", "$1 $2").replace('_', ' ')
                        .toLowerCase(Locale.ROOT);
                yield Character.toUpperCase(words.charAt(0)) + words.substring(1);
            }
        };
    }

    private static String statusTone(String status) {
        String s = status == null ? "" : status.toLowerCase(Locale.ROOT);
        if (s.contains("unpushed")) return "amber";
        if (s.contains("pushed")) return "green";
        if (s.contains("integrated")) return "blue";
        return "gray";
    }

    private static String changeMark(String type) {
        String t = type == null ? "" : type.toLowerCase(Locale.ROOT);
        return switch (t) {
            case "added", "untracked" -> "A";
            case "deleted" -> "D";
            case "renamed" -> "R";
            case "modified" -> "M";
            default -> t.isBlank() ? "?" : t.substring(0, 1).toUpperCase(Locale.ROOT);
        };
    }

    private static String changeTone(String type) {
        String t = type == null ? "" : type.toLowerCase(Locale.ROOT);
        return switch (t) {
            case "added", "untracked" -> "green";
            case "deleted" -> "red";
            case "renamed" -> "blue";
            case "modified" -> "amber";
            default -> "gray";
        };
    }

    private static Label pill(String text, String tone) {
        Label label = new Label(text);
        label.setStyle("-fx-font-size: 10px; -fx-font-weight: bold; -fx-text-fill: " + foreground(tone)
                + "; -fx-background-color: " + background(tone)
                + "; -fx-background-radius: 9; -fx-padding: 1 7 1 7;");
        return label;
    }

    private static String foreground(String tone) {
        return switch (tone) {
            case "amber" -> "#d29922";
            case "blue" -> "#79c0ff";
            case "green" -> "#3fb950";
            case "red" -> "#f85149";
            default -> "#8b949e";
        };
    }

    private static String background(String tone) {
        return switch (tone) {
            case "amber" -> "rgba(210,153,34,0.16)";
            case "blue" -> "rgba(56,139,253,0.16)";
            case "green" -> "rgba(46,160,67,0.16)";
            case "red" -> "rgba(248,81,73,0.16)";
            default -> "rgba(110,118,129,0.16)";
        };
    }

    private static String count(int n, String singular, String plural) {
        return n + " " + (n == 1 ? singular : plural);
    }
}
