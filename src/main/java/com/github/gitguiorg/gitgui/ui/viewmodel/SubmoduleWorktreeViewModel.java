package com.github.gitguiorg.gitgui.ui.viewmodel;

import com.github.gitguiorg.gitgui.model.SubmoduleInfo;
import com.github.gitguiorg.gitgui.model.WorktreeInfo;
import com.github.gitguiorg.gitgui.service.RepoSession;
import com.github.gitguiorg.gitgui.ui.Fx;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

/**
 * Submodules and worktrees of one open repository: the lists refreshed with the status and the actions behind them.
 * All properties are touched on the FX thread, like {@link RepoViewModel}.
 */
final class SubmoduleWorktreeViewModel {
    private final RepoSession session;
    private final NetworkViewModel network;
    private final Consumer<Throwable> errorHandler;

    private final ObservableList<SubmoduleInfo> submodules = FXCollections.observableArrayList();
    private final ObservableList<WorktreeInfo> worktrees = FXCollections.observableArrayList();
    private final BooleanProperty worktree = new SimpleBooleanProperty(false);
    private final BooleanProperty submoduleRecurse = new SimpleBooleanProperty(false);

    SubmoduleWorktreeViewModel(RepoSession session, NetworkViewModel network, Consumer<Throwable> errorHandler) {
        this.session = session;
        this.network = network;
        this.errorHandler = errorHandler;
    }

    ObservableList<SubmoduleInfo> submodules() { return submodules; }
    ObservableList<WorktreeInfo> worktrees() { return worktrees; }

    /** True when the open folder is a linked worktree, so the tab title can say so. */
    ReadOnlyBooleanProperty worktreeProperty() { return worktree; }

    /** Whether pull and checkout recurse into the submodules ({@code submodule.recurse}). */
    BooleanProperty submoduleRecurseProperty() { return submoduleRecurse; }

    /** Replaces the lists from a status snapshot; {@code linkedWorktree} is true for a linked worktree. */
    void apply(List<SubmoduleInfo> submodules, List<WorktreeInfo> worktrees, boolean recurse, boolean linkedWorktree) {
        this.submodules.setAll(submodules);
        this.worktrees.setAll(worktrees);
        this.submoduleRecurse.set(recurse);
        this.worktree.set(linkedWorktree);
    }

    /** Init + update the given submodules (all when empty), with progress in the toolbar. */
    void updateSubmodules(List<String> paths) {
        network.execute("Updating submodules…", (git, progress, token) -> git.submoduleUpdate(paths, progress, token));
    }

    /** Same as above, updating also the submodules inside the submodules. */
    void updateSubmodulesRecursively(List<String> paths) {
        network.execute("Updating submodules…", (git, progress, token) -> git.submoduleUpdate(paths, true, progress, token));
    }

    void addSubmodule(String url, String path) {
        network.execute("Adding submodule…", (git, progress, token) -> git.submoduleAdd(url, path));
    }

    void removeSubmodule(String path) {
        run(() -> session.git().submoduleRemove(path));
    }

    void setSubmoduleRecurse(boolean on) {
        run(() -> {
            session.git().setSubmoduleRecurse(on);
            Fx.run(() -> submoduleRecurse.set(on));
        });
    }

    void addWorktree(Path path, String newBranch, String startPoint) {
        run(() -> session.git().worktreeAdd(path, newBranch, startPoint));
    }

    void removeWorktree(Path path, boolean force) {
        run(() -> session.git().worktreeRemove(path, force));
    }

    /** Remove; when git refuses because of local changes, {@code onDirty} runs on the FX thread with its message. */
    void tryRemoveWorktree(Path path, Consumer<String> onDirty) {
        session.async(() -> {
            try {
                session.git().worktreeRemove(path, false);
                return (String) null;
            } catch (com.github.gitguiorg.gitgui.git.GitException e) {
                if (e.stderr() != null && e.stderr().contains("--force")) return e.stderr();
                throw e;
            }
        }).whenComplete((msg, err) -> {
            session.notifyChanged();
            if (err != null) errorHandler.accept(err);
            else if (msg != null) Fx.run(() -> onDirty.accept(msg));
        });
    }

    void lockWorktree(Path path, String reason) {
        run(() -> session.git().worktreeLock(path, reason));
    }

    void unlockWorktree(Path path) { run(() -> session.git().worktreeUnlock(path)); }

    void moveWorktree(Path from, Path to) {
        run(() -> session.git().worktreeMove(from, to));
    }

    void pruneWorktrees() { run(() -> session.git().worktreePrune()); }

    /** True when the first worktree is not the open repository itself, i.e. this is a linked worktree. */
    boolean isLinkedWorktree(List<WorktreeInfo> worktrees) {
        return !worktrees.isEmpty() && !isSamePath(worktrees.get(0).path(), session.root());
    }

    private static boolean isSamePath(String fromGit, Path known) {
        try {
            return Files.isSameFile(Path.of(fromGit), known);
        } catch (java.io.IOException | RuntimeException e) {
            return Path.of(fromGit).toAbsolutePath().normalize()
                    .equals(known.toAbsolutePath().normalize());
        }
    }

    private void run(Runnable op) {
        session.mutate(op).exceptionally(e -> {
            errorHandler.accept(e);
            return null;
        });
    }
}
