package com.github.gitguiorg.gitgui.ui.view;

import com.github.gitguiorg.gitgui.git.GitClient;
import com.github.gitguiorg.gitgui.model.Commit;
import com.github.gitguiorg.gitgui.model.Operation;
import com.github.gitguiorg.gitgui.ui.Fx;
import com.github.gitguiorg.gitgui.ui.viewmodel.LogViewModel;
import com.github.gitguiorg.gitgui.ui.viewmodel.LogViewModel.CommitRow;
import com.github.gitguiorg.gitgui.ui.viewmodel.RepoViewModel;
import java.io.File;
import java.util.List;
import java.util.Optional;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.ContextMenuEvent;
import javafx.stage.FileChooser;

public final class LogView extends ListView<CommitRow> {
    private final RepoViewModel repo;
    private final LogViewModel log;

    public LogView(RepoViewModel repo) {
        this.repo = repo;
        this.log = repo.log();
        setItems(log.rows());
        getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);   // Ctrl+click to pick two commits
        setFixedCellSize(CommitCell.ROW_H);
        setPlaceholder(new EmptyState("mdi2g-git", "No commits", "There are no commits in this repository yet.",
                null, null));
        setCellFactory(lv -> new CommitCell(log, this::showMenu, log::loadMore, () -> log.rows().size()));
        getSelectionModel().selectedItemProperty().addListener((o, a, row) ->
                log.selectedProperty().set(row == null ? null : row.commit()));
        log.graphLanesProperty().addListener((o, a, b) -> refresh());
        log.stackTickProperty().addListener((o, a, b) -> refresh());
        log.bisectBadProperty().addListener((o, a, b) -> refresh());
        log.bisectGoodProperty().addListener((javafx.collections.SetChangeListener<String>) c -> refresh());
        log.starredProperty().addListener((javafx.collections.SetChangeListener<String>) c -> refresh());
    }

    private void showMenu(Commit c, ContextMenuEvent e) {
        MenuItem copy = item("Copy hash", () -> {
            ClipboardContent cc = new ClipboardContent();
            cc.putString(c.hash());
            Clipboard.getSystemClipboard().setContent(cc);
        });
        MenuItem star = item(log.isStarred(c.hash()) ? "Remove star" : "Star", () -> repo.toggleStar(c.hash()));
        MenuItem checkout = item("Checkout " + c.shortHash(), () -> repo.checkoutCommit(c.hash()));
        var status = repo.statusProperty().get();
        checkout.setDisable(status != null && c.hash().equals(status.headOid()));
        MenuItem branch = item("New Branch…", () -> Dialogs.askBranch(c.shortHash())
                .ifPresent(r -> repo.createBranch(r.name(), c.hash(), r.checkout())));
        MenuItem tag = item("Add Tag…", () -> Dialogs.askTag()
                .ifPresent(t -> repo.createTag(t[0], c.hash(), t[1].isEmpty() ? null : t[1])));
        MenuItem merge = item("Merge into current branch…", () -> Dialogs.askMerge(c.shortHash())
                .ifPresent(o -> repo.merge(c.hash(), o.noFf(), o.squash())));
        MenuItem pick = item("Cherry-pick", () -> repo.cherryPick(c.hash()));
        MenuItem revert = item("Revert", () -> repo.revert(c.hash()));
        MenuItem bisectGood = item("Bisect: mark as good", () -> repo.bisectCommit(c.hash(), true));
        MenuItem bisectBad = item("Bisect: mark as bad", () -> repo.bisectCommit(c.hash(), false));
        MenuItem bisectSkip = item("Bisect: skip the checked-out commit", repo::bisectSkip);
        MenuItem bisectReset = item("Bisect: reset", repo::bisectReset);
        boolean bisecting = repo.operationProperty().get() == Operation.BISECT;
        bisectSkip.setDisable(!bisecting);
        bisectReset.setDisable(!bisecting);
        boolean stagedDirty = status != null && status.entries().stream().anyMatch(entry -> entry.isStaged());
        Menu reset = new Menu("Reset current branch to here",
                null,
                item("Soft", () -> repo.reset(c.hash(), GitClient.ResetMode.SOFT)),
                item("Mixed", () -> {
                    if (stagedDirty && confirm("Mixed reset to " + c.shortHash()
                            + "? The index will be reset and the staged changes will be unstaged.").isEmpty()) return;
                    repo.reset(c.hash(), GitClient.ResetMode.MIXED);
                }),
                item("Keep (keeps local changes)", () -> repo.reset(c.hash(), GitClient.ResetMode.KEEP)),
                item("Hard (discards changes)", () -> confirm("Hard reset to " + c.shortHash() + "? Uncommitted changes will be lost.")
                        .ifPresent(ok -> repo.reset(c.hash(), GitClient.ResetMode.HARD))));
        MenuItem files = item("Explore files…", () -> repo.openCommitFiles(c));
        MenuItem exportPatch = item("Export patch…", () -> exportPatch(c));
        MenuItem comparePicked = item("Compare selected commits", () -> {
            List<Integer> indices = new java.util.ArrayList<>(getSelectionModel().getSelectedIndices());
            if (indices.size() != 2) return;
            indices.sort(null);   // the log is newest first: the higher row is the older side of the comparison
            Commit newer = getItems().get(indices.get(0)).commit();
            Commit older = getItems().get(indices.get(1)).commit();
            repo.openCompare(older.hash(), newer.hash());
        });
        comparePicked.setDisable(getSelectionModel().getSelectedIndices().size() != 2);
        MenuItem compareWorking = item("Compare with working tree", () -> repo.openCompare(c.hash(), null));
        MenuItem compareHead = item("Compare with HEAD", () -> repo.openCompare(c.hash(), "HEAD"));
        String branchName = status == null || status.isDetached() ? "the current branch" : status.branch();
        MenuItem rebaseOnto = item("Rebase current branch onto this", () -> {
            if (confirm("Rebase " + branchName + " onto " + c.shortHash() + "?").isPresent()) repo.rebaseOnto(c.hash());
        });
        MenuItem squash = item("Squash…", () -> repo.prepareSquash(c, commits -> {
            Commit first = commits.get(0);
            String message = first.body().isBlank() ? first.subject() : first.subject() + "\n\n" + first.body();
            Dialogs.askSquash(commits, message).ifPresent(m -> repo.squash(c, m));
        }));
        MenuItem irebase = item("Interactive rebase from here…", () -> repo.prepareInteractiveRebase(c,
                plan -> InteractiveRebaseDialog.show(plan).ifPresent(steps -> repo.runInteractiveRebase(plan.from(), steps))));
        repo.session().async(() -> new boolean[]{repo.session().git().isAncestorOfHead(c.hash()),
                repo.session().git().isAlreadyInHead(c.hash())}).whenComplete((r, err) -> {
            if (err != null) return;
            Fx.run(() -> {
                merge.setDisable(r[0]);
                rebaseOnto.setDisable(r[0]);
                pick.setDisable(c.isMerge() || r[1]);
            });
        });
        ContextMenu menu = new ContextMenu(copy, star, files, exportPatch, comparePicked, compareWorking, compareHead,
                new SeparatorMenuItem(), checkout, branch, tag,
                new SeparatorMenuItem(), merge, rebaseOnto, irebase, squash, pick, revert,
                new SeparatorMenuItem(), bisectGood, bisectBad, bisectSkip, bisectReset,
                new SeparatorMenuItem(), reset);
        menu.show(getScene().getWindow(), e.getScreenX(), e.getScreenY());
    }

    /** Saves the mbox patch of one commit ({@code git format-patch -1 <hash>}) to the chosen file. */
    private void exportPatch(Commit c) {
        FileChooser fc = new FileChooser();
        fc.setTitle("Export patch of " + c.shortHash());
        fc.setInitialFileName(c.shortHash() + "-" + safeName(c.subject()) + ".patch");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Patch files", "*.patch"));
        File target = fc.showSaveDialog(getScene() == null ? null : getScene().getWindow());
        if (target == null) return;
        repo.session().async(() -> {
            repo.session().git().formatPatch(c.hash(), target.toPath());
            return null;
        }).whenComplete((v, err) -> { if (err != null) repo.reportError(err); });
    }

    private static String safeName(String subject) {
        String s = subject == null ? "" : subject.replaceAll("[^A-Za-z0-9._-]+", "-");
        return s.length() > 60 ? s.substring(0, 60) : s;
    }

    private static MenuItem item(String text, Runnable action) {
        MenuItem mi = new MenuItem(text);
        mi.setOnAction(e -> action.run());
        return mi;
    }

    private static Optional<String> ask(String title, String prompt, String initial) {
        TextInputDialog d = new TextInputDialog(initial);
        d.setTitle(title);
        d.setHeaderText(prompt);
        return d.showAndWait().map(String::strip).filter(s -> !s.isEmpty());
    }

    private static Optional<ButtonType> confirm(String message) {
        Alert a = new Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.OK, ButtonType.CANCEL);
        return a.showAndWait().filter(b -> b == ButtonType.OK);
    }
}
