package com.github.gitguiorg.gitgui.ui.viewmodel;

import com.github.gitguiorg.gitgui.model.GitRef;
import com.github.gitguiorg.gitgui.model.RepoStatus;
import com.github.gitguiorg.gitgui.service.RepoSession;
import com.github.gitguiorg.gitgui.ui.Fx;
import com.github.gitguiorg.gitgui.util.Hashes;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

/** State of one open repository, kept in sync with the session. All properties are touched on the FX thread. */
public final class RepoViewModel {
    private record Snapshot(RepoStatus status, List<GitRef> refs, List<com.github.gitguiorg.gitgui.model.StashEntry> stashes, com.github.gitguiorg.gitgui.model.Operation op, String opMessage,
                            java.util.Map<String, String> remotes,
                            List<com.github.gitguiorg.gitgui.model.SubmoduleInfo> submodules, List<com.github.gitguiorg.gitgui.model.WorktreeInfo> worktrees,
                            java.util.Set<String> ignoredTracked, boolean submoduleRecurse,
                            com.github.gitguiorg.gitgui.git.GitClient.BisectState bisect, List<String> starred) {}

    private final RepoSession session;
    private final com.github.gitguiorg.gitgui.service.Settings settings;
    private final com.github.gitguiorg.gitgui.service.DiffPreferences diffPrefs;
    private final Consumer<Throwable> errorHandler;
    private final ObjectProperty<RepoStatus> status = new SimpleObjectProperty<>();
    private final ObservableList<GitRef> refs = FXCollections.observableArrayList();
    private final ObservableList<com.github.gitguiorg.gitgui.model.StashEntry> stashes = FXCollections.observableArrayList();
    private final AtomicLong refreshSeq = new AtomicLong();

    private final LogViewModel log;
    private String lastSignature = "";

    private final DiffViewModel diff;
    private final ChangesViewModel changes;
    private final NetworkViewModel network;
    private final ObservableList<String> remoteNames = FXCollections.observableArrayList();
    private volatile java.util.Map<String, String> remoteUrls = java.util.Map.of();
    private final ObjectProperty<com.github.gitguiorg.gitgui.model.Operation> operation = new SimpleObjectProperty<>(com.github.gitguiorg.gitgui.model.Operation.NONE);
    private final ObjectProperty<com.github.gitguiorg.gitgui.git.GitClient.BisectState> bisectState =
            new SimpleObjectProperty<>(new com.github.gitguiorg.gitgui.git.GitClient.BisectState(null, List.of(), 0));
    private String operationMessage;
    private java.util.function.Consumer<com.github.gitguiorg.gitgui.model.StatusEntry> resolverOpener = e -> {};

    public RepoViewModel(RepoSession session, com.github.gitguiorg.gitgui.service.Settings settings,
                         com.github.gitguiorg.gitgui.service.DiffPreferences diffPrefs, Consumer<Throwable> errorHandler) {
        this.session = session;
        this.settings = settings;
        this.butEnabled.set(initialButEnabled());
        this.diffPrefs = diffPrefs;
        this.errorHandler = errorHandler;
        this.log = new LogViewModel(session, () -> settings.logPageSize, errorHandler);
        this.diff = new DiffViewModel(session, diffPrefs, errorHandler);
        this.changes = new ChangesViewModel(this);
        this.network = new NetworkViewModel(this, settings);
        this.submoduleWorktrees = new SubmoduleWorktreeViewModel(session, network, errorHandler);
        session.onChanged(this::refresh);
        refresh();
        network.start();
    }

    public RepoSession session() { return session; }
    public com.github.gitguiorg.gitgui.service.Settings settings() { return settings; }
    public LogViewModel log() { return log; }
    public DiffViewModel diff() { return diff; }
    public ChangesViewModel changes() { return changes; }
    public NetworkViewModel network() { return network; }
    public ObjectProperty<com.github.gitguiorg.gitgui.model.Operation> operationProperty() { return operation; }
    public String operationMessage() { return operationMessage; }
    public void setResolverOpener(java.util.function.Consumer<com.github.gitguiorg.gitgui.model.StatusEntry> o) { this.resolverOpener = o; }
    public void openResolver(com.github.gitguiorg.gitgui.model.StatusEntry e) { resolverOpener.accept(e); }

    private java.util.function.BiConsumer<String, String> blameOpener = (p, r) -> {};
    private java.util.function.Consumer<String> fileHistoryOpener = p -> {};
    private java.util.function.Consumer<com.github.gitguiorg.gitgui.model.Commit> commitFilesOpener = c -> {};
    public void setBlameOpener(java.util.function.BiConsumer<String, String> o) { this.blameOpener = o; }
    public void setFileHistoryOpener(java.util.function.Consumer<String> o) { this.fileHistoryOpener = o; }
    public void setCommitFilesOpener(java.util.function.Consumer<com.github.gitguiorg.gitgui.model.Commit> o) { this.commitFilesOpener = o; }

    private java.util.function.BiConsumer<String, String> compareOpener = (a, b) -> {};
    public void setCompareOpener(java.util.function.BiConsumer<String, String> o) { this.compareOpener = o; }

    private final javafx.beans.property.BooleanProperty butEnabled = new javafx.beans.property.SimpleBooleanProperty();
    private GitButlerViewModel butler;
    private javafx.beans.binding.BooleanBinding butWorkspace;

    /** Whether GitButler is on for this repository: the user's choice, else automatic when it already uses GitButler. */
    public javafx.beans.property.ReadOnlyBooleanProperty butEnabledProperty() { return butEnabled; }

    /** Remembers the user's choice for this repository only. */
    public void setButEnabled(boolean on) {
        settings.gitButlerRepos.put(butKey(), on);
        butEnabled.set(on);
    }

    private String butKey() { return session.root().toAbsolutePath().normalize().toString(); }

    private boolean initialButEnabled() {
        Boolean chosen = settings.gitButlerRepos.get(butKey());
        if (chosen != null) return chosen;
        if (!settings.gitButlerAuto || !java.nio.file.Files.isDirectory(session.root().resolve(".git").resolve("gitbutler"))) {
            return false;
        }
        try {
            return com.github.gitguiorg.gitgui.git.ButClient.find(session.root(), settings.butPath) != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** The GitButler model of this repository, created on first use. */
    public GitButlerViewModel gitButler() {
        if (butler == null) butler = new GitButlerViewModel(this);
        return butler;
    }

    /** True while HEAD is the GitButler workspace branch: the repository is managed by GitButler. */
    public javafx.beans.binding.BooleanBinding butWorkspaceProperty() {
        if (butWorkspace == null) {
            butWorkspace = javafx.beans.binding.Bindings.createBooleanBinding(() -> {
                RepoStatus s = status.get();
                return butEnabled.get() || (s != null && "gitbutler/workspace".equals(s.branch()));
            }, status, butEnabled);
        }
        return butWorkspace;
    }

    public boolean isButWorkspace() { return butWorkspaceProperty().get(); }

    private Runnable gitButlerOpener = () -> {};
    public void setGitButlerOpener(Runnable o) { this.gitButlerOpener = o; }
    /** Opens the GitButler workspace tab; the view runs but status when built. */
    public void openGitButler() { gitButlerOpener.run(); }
    /** Opens a comparison in a new tab; a null {@code b} compares {@code a} with the working tree. */
    public void openCompare(String a, String b) { if (a != null) compareOpener.accept(a, b); }
    /** Diff context lines from the shared preferences, so the diff bar and the other tabs agree. */
    public int diffContextLines() { return diffPrefs.contextLinesProperty().get(); }
    /** @param rev revision to blame at, or null for the working tree */
    public void openBlame(String path, String rev) { blameOpener.accept(path, rev); }
    public void openFileHistory(String path) { fileHistoryOpener.accept(path); }
    /** Opens the file tree of a commit in a new tab. */
    public void openCommitFiles(com.github.gitguiorg.gitgui.model.Commit c) { if (c != null) commitFilesOpener.accept(c); }

    // ---- commit composer of GitButler workspaces

    private final javafx.beans.property.ReadOnlyObjectWrapper<List<String>> composerPaths =
            new javafx.beans.property.ReadOnlyObjectWrapper<>(List.of());
    private final javafx.beans.property.ReadOnlyStringWrapper composerBranch =
            new javafx.beans.property.ReadOnlyStringWrapper("");
    private Runnable composerFocus = () -> { };

    /** Files the next commit will include; an empty list means every uncommitted change. */
    public javafx.beans.property.ReadOnlyObjectProperty<List<String>> composerPathsProperty() {
        return composerPaths.getReadOnlyProperty();
    }

    /** Branch the composer should preselect, or an empty string to keep the current choice. */
    public javafx.beans.property.ReadOnlyStringProperty composerBranchProperty() {
        return composerBranch.getReadOnlyProperty();
    }

    /** Called by the view owning the composer when it has to take the focus; null clears the handler. */
    public void setComposerFocusHandler(Runnable handler) {
        composerFocus = handler == null ? () -> { } : handler;
    }

    /**
     * Fills the commit composer and focuses it, so actions elsewhere in the workspace (a change row, a drop on a
     * branch) end up in the same commit flow instead of opening a dialog.
     *
     * @param paths  files to commit, empty for every uncommitted change
     * @param branch branch to preselect, or null to keep the current one
     */
    public void composeCommit(List<String> paths, String branch) {
        composerPaths.set(paths == null ? List.of() : List.copyOf(paths));
        composerBranch.set(branch == null ? "" : branch);
        composerFocus.run();
    }

    /** Sends the focus to the commit composer, when the repository view is showing it. */
    public void focusComposer() {
        composerFocus.run();
    }

    /** Forgets scope and branch hint, so the next commit includes every change again. */
    public void clearComposerScope() {
        composerPaths.set(List.of());
        composerBranch.set("");
    }

    // ---- submodules / worktrees

    private final SubmoduleWorktreeViewModel submoduleWorktrees;
    private java.util.function.Consumer<java.nio.file.Path> repoOpener = p -> {};

    public ObservableList<com.github.gitguiorg.gitgui.model.SubmoduleInfo> submodules() { return submoduleWorktrees.submodules(); }
    public ObservableList<com.github.gitguiorg.gitgui.model.WorktreeInfo> worktrees() { return submoduleWorktrees.worktrees(); }

    /** True when the open folder is a linked worktree, so the tab title can say so. */
    public ReadOnlyBooleanProperty worktreeProperty() { return submoduleWorktrees.worktreeProperty(); }
    public void setRepoOpener(java.util.function.Consumer<java.nio.file.Path> o) { this.repoOpener = o; }
    public void openRepo(java.nio.file.Path dir) { repoOpener.accept(dir); }
    public java.nio.file.Path root() { return session.root(); }

    /** Init + update the given submodules (all when empty), with progress in the toolbar. */
    public void updateSubmodules(List<String> paths) { submoduleWorktrees.updateSubmodules(paths); }

    /** Same as above, updating also the submodules inside the submodules. */
    public void updateSubmodulesRecursively(List<String> paths) {
        submoduleWorktrees.updateSubmodulesRecursively(paths);
    }

    public void addSubmodule(String url, String path) { submoduleWorktrees.addSubmodule(url, path); }

    public void removeSubmodule(String path) { submoduleWorktrees.removeSubmodule(path); }

    /** Whether pull and checkout recurse into the submodules ({@code submodule.recurse}). */
    public BooleanProperty submoduleRecurseProperty() { return submoduleWorktrees.submoduleRecurseProperty(); }

    public void setSubmoduleRecurse(boolean on) { submoduleWorktrees.setSubmoduleRecurse(on); }

    public void addWorktree(java.nio.file.Path path, String newBranch, String startPoint) {
        submoduleWorktrees.addWorktree(path, newBranch, startPoint);
    }

    public void removeWorktree(java.nio.file.Path path, boolean force) {
        submoduleWorktrees.removeWorktree(path, force);
    }

    /** Remove; when git refuses because of local changes, {@code onDirty} runs on the FX thread with its message. */
    public void tryRemoveWorktree(java.nio.file.Path path, Consumer<String> onDirty) {
        submoduleWorktrees.tryRemoveWorktree(path, onDirty);
    }

    public void lockWorktree(java.nio.file.Path path, String reason) {
        submoduleWorktrees.lockWorktree(path, reason);
    }

    public void unlockWorktree(java.nio.file.Path path) { submoduleWorktrees.unlockWorktree(path); }

    public void moveWorktree(java.nio.file.Path from, java.nio.file.Path to) {
        submoduleWorktrees.moveWorktree(from, to);
    }

    public void pruneWorktrees() { submoduleWorktrees.pruneWorktrees(); }

    public void rebaseOnto(String rev) {
        conflictAware("Rebase", () -> {
            RepoStatus st = session.git().status(false);
            if (!st.entries().isEmpty()) {
                throw new IllegalStateException("Commit or stash your changes before rebasing.");
            }
            return session.git().rebaseOnto(rev);
        });
    }

    /** Loads the commits a squash would fold together, then calls {@code onReady} on the FX thread. */
    public void prepareSquash(com.github.gitguiorg.gitgui.model.Commit from, java.util.function.Consumer<List<com.github.gitguiorg.gitgui.model.Commit>> onReady) {
        session.async(() -> {
            if (!session.git().isAncestorOfHead(from.hash())) {
                throw new IllegalStateException("This commit is not in the history of the current branch.");
            }
            List<com.github.gitguiorg.gitgui.model.Commit> commits = session.git().rebaseCandidates(from);
            if (commits.size() < 2) {
                throw new IllegalStateException("There is nothing to squash: select a commit with later commits on top of it.");
            }
            return commits;
        }).whenComplete((commits, err) -> {
            if (err != null) errorHandler.accept(err);
            else Fx.run(() -> onReady.accept(commits));
        });
    }

    public void squash(com.github.gitguiorg.gitgui.model.Commit from, String message) {
        conflictAware("Squash", () -> session.git().squashFrom(from, message));
    }

    /** Adds or removes the star of a commit. */
    public void toggleStar(String hash) {
        boolean starred = !log.isStarred(hash);
        run(() -> session.git().setStarred(hash, starred));
    }

    // ---- branch / tag / remote management

    public ObservableList<String> remoteNames() { return remoteNames; }
    public java.util.Map<String, String> remoteUrls() { return remoteUrls; }

    public void createBranch(String name, String startPoint, boolean checkout) {
        run(() -> session.git().createBranch(name, startPoint, checkout));
    }

    public void createTag(String name, String target, String message) {
        run(() -> session.git().createTag(name, target, message));
    }

    public void renameBranch(String oldName, String newName) { run(() -> session.git().renameBranch(oldName, newName)); }

    public void deleteBranch(String name, boolean force) { deleteBranch(name, force, null); }

    /**
     * Delete a branch; when {@code tip} is given, a banner offers to recreate it at that hash.
     */
    public void deleteBranch(String name, boolean force, String tip) {
        session.async(() -> { session.git().deleteBranch(name, force); return null; }).whenComplete((v, err) -> {
            session.notifyChanged();
            if (err != null) { errorHandler.accept(err); return; }
            if (tip != null && !tip.isBlank()) {
                offerUndo("Deleted branch " + name + " (was " + Hashes.shortHash(tip) + ").",
                        () -> session.git().createBranch(name, tip, false));
            }
        });
    }

    /** Safe delete; when git refuses because the branch is unmerged, {@code onNotMerged} runs on the FX thread. */
    public void tryDeleteBranch(String name, java.util.function.Consumer<String> onNotMerged) {
        session.async(() -> {
            try {
                session.git().deleteBranch(name, false);
                return (String) null;
            } catch (com.github.gitguiorg.gitgui.git.GitException e) {
                if (e.stderr() != null && e.stderr().contains("not fully merged")) return e.stderr();
                throw e;
            }
        }).whenComplete((msg, err) -> {
            session.notifyChanged();
            if (err != null) errorHandler.accept(err);
            else if (msg != null) Fx.run(() -> onNotMerged.accept(msg));
        });
    }

    public void deleteTag(String name) { run(() -> session.git().deleteTag(name)); }
    public void setUpstream(String branch, String upstream) { run(() -> session.git().setUpstream(branch, upstream)); }
    public void unsetUpstream(String branch) { run(() -> session.git().unsetUpstream(branch)); }
    public void addRemote(String name, String url) { run(() -> session.git().addRemote(name, url)); }
    public void removeRemote(String name) { run(() -> session.git().removeRemote(name)); }
    public void renameRemote(String oldName, String newName) { run(() -> session.git().renameRemote(oldName, newName)); }
    public void setRemoteUrl(String name, String url) { run(() -> session.git().setRemoteUrl(name, url)); }

    /** Loads the commits an interactive rebase from {@code from} would replay, then calls {@code onReady} on the FX thread. */
    public void prepareInteractiveRebase(com.github.gitguiorg.gitgui.model.Commit from,
                                         java.util.function.Consumer<com.github.gitguiorg.gitgui.model.RebasePlan> onReady) {
        session.async(() -> {
            if (!session.git().isAncestorOfHead(from.hash())) {
                throw new IllegalStateException("This commit is not in the history of the current branch.");
            }
            return new com.github.gitguiorg.gitgui.model.RebasePlan(from, session.git().rebaseCandidates(from),
                    session.git().isOnRemote(from.hash()));
        }).whenComplete((plan, err) -> Fx.run(() -> {
            if (err != null) errorHandler.accept(err);
            else if (plan.commits().isEmpty()) errorHandler.accept(new IllegalStateException("Nothing to rebase."));
            else onReady.accept(plan);
        }));
    }

    public void runInteractiveRebase(com.github.gitguiorg.gitgui.model.Commit from,
                                     java.util.List<com.github.gitguiorg.gitgui.model.RebaseStep> steps) {
        conflictAware("Rebase", () -> session.git().rebaseInteractive(from, steps));
    }

    public void operationContinue() {
        com.github.gitguiorg.gitgui.model.Operation op = operation.get();
        conflictAware(op.title, () -> session.git().operationContinue(op));
    }

    public void operationAbort() {
        com.github.gitguiorg.gitgui.model.Operation op = operation.get();
        run(() -> session.git().operationAbort(op));
    }

    // ---- bisect

    public ReadOnlyObjectProperty<com.github.gitguiorg.gitgui.git.GitClient.BisectState> bisectStateProperty() {
        return bisectState;
    }

    /** Marks the checked-out candidate as bad ({@code good == false}) or good. */
    public void bisectCurrent(boolean good) { run(() -> session.git().bisectMark("HEAD", good)); }

    /** Starts a bisect from this commit if none is running, then marks it good or bad. */
    public void bisectCommit(String hash, boolean good) { run(() -> session.git().bisectMark(hash, good)); }

    public void bisectSkip() { run(() -> session.git().bisectSkip()); }

    public void bisectReset() { run(() -> session.git().bisectReset()); }
    /** Opens a repository-relative file with the system default application. */
    public void openInEditor(String path) {
        java.nio.file.Path file = session.root().resolve(path);
        if (!java.nio.file.Files.isRegularFile(file)) {
            errorHandler.accept(new java.io.IOException("File not found: " + file));
            return;
        }
        session.async(() -> {
            com.github.gitguiorg.gitgui.util.SystemActions.open(file);
            return null;
        }).whenComplete((v, err) -> {
            if (err != null) errorHandler.accept(err);
        });
    }

    public void reportError(Throwable e) { errorHandler.accept(e); }
    public String name() { return session.name(); }
    public ReadOnlyObjectProperty<RepoStatus> statusProperty() { return status; }
    public ObservableList<GitRef> refs() { return refs; }
    public ObservableList<com.github.gitguiorg.gitgui.model.StashEntry> stashes() { return stashes; }

    /** Reloads status and refs in the background; stale results are discarded. */
    public void refresh() {
        long seq = refreshSeq.incrementAndGet();
        boolean withIgnored = changes.showIgnoredProperty().get();
        session.async(() -> {
            RepoStatus st = session.git().status(withIgnored);
            com.github.gitguiorg.gitgui.model.Operation op = session.git().operation();
            var bisect = op == com.github.gitguiorg.gitgui.model.Operation.BISECT
                    ? session.git().bisectState()
                    : new com.github.gitguiorg.gitgui.git.GitClient.BisectState(null, List.of(), 0);
            return new Snapshot(st, session.git().refs(), session.git().stashList(), op, session.git().operationMessage(),
                    session.git().remoteUrls(), session.git().submodules(st), session.git().worktrees(),
                    session.git().ignoredTrackedFiles(), session.git().submoduleRecurse(), bisect,
                    session.git().starredCommits());
        })
                .whenComplete((snap, err) -> {
                    if (err != null) { errorHandler.accept(err); return; }
                    if (seq != refreshSeq.get()) return;
                    Fx.run(() -> {
                        status.set(snap.status());
                        changes.update(snap.status(), snap.ignoredTracked());
                        refs.setAll(snap.refs());
                        stashes.setAll(snap.stashes());
                        submoduleWorktrees.apply(snap.submodules(), snap.worktrees(), snap.submoduleRecurse(),
                                submoduleWorktrees.isLinkedWorktree(snap.worktrees()));
                        operationMessage = snap.opMessage();
                        operation.set(snap.op());
                        bisectState.set(snap.bisect());
                        log.setBisect(snap.bisect().bad(), snap.bisect().good());
                        log.setStarred(snap.starred());
                        remoteUrls = snap.remotes();
                        List<String> rn = new java.util.ArrayList<>(snap.remotes().keySet());
                        if (!rn.equals(remoteNames)) remoteNames.setAll(rn);
                        String sig = snap.status().headOid() + "|" + snap.refs().stream()
                                .map(r -> r.fullName() + "=" + r.hash()).sorted().collect(java.util.stream.Collectors.joining(","));
                        if (!sig.equals(lastSignature)) {
                            lastSignature = sig;
                            log.reload();
                        }
                    });
                });
    }

    /** Runs a mutating git operation in the background, reporting failures through the error handler. */
    public void run(Runnable op) {
        session.mutate(op).exceptionally(e -> { errorHandler.accept(e); return null; });
    }

    public void checkout(GitRef ref) {
        if (ref.type() == GitRef.Type.LOCAL_BRANCH && !ref.head()) {
            run(() -> session.git().checkoutBranch(ref.shortName()));
        } else if (ref.type() == GitRef.Type.REMOTE_BRANCH) {
            String remote = GitRef.remoteOf(ref.shortName(), remoteNames);
            String local = GitRef.branchOf(ref.shortName(), remote);
            boolean exists = refs.stream().anyMatch(r -> r.type() == GitRef.Type.LOCAL_BRANCH && r.shortName().equals(local));
            if (exists) run(() -> session.git().checkoutBranch(local));
            else run(() -> session.git().createBranch(local, ref.shortName(), true));
        }
    }

    public void stash(String message, boolean includeUntracked) {
        run(() -> session.git().stashPush(message, includeUntracked));
    }

    public void stashApply(String ref) { conflictAware("Stash apply", () -> session.git().stashApply(ref)); }
    public void stashPop(String ref) { conflictAware("Stash pop", () -> session.git().stashPop(ref)); }
    public void stashDrop(String ref) { stashDrop(ref, null); }

    /** Drop a stash; when {@code entry} is given, a banner offers to put it back with {@code stash store}. */
    public void stashDrop(String ref, com.github.gitguiorg.gitgui.model.StashEntry entry) {
        session.async(() -> { session.git().stashDrop(ref); return null; }).whenComplete((v, err) -> {
            session.notifyChanged();
            if (err != null) { errorHandler.accept(err); return; }
            if (entry != null) {
                offerUndo("Dropped " + entry.ref() + " (" + Hashes.shortHash(entry.hash()) + ").",
                        () -> session.git().stashStore(entry.hash(), entry.message()));
            }
        });
    }
    public void showStash(com.github.gitguiorg.gitgui.model.StashEntry s) { diff.showStash(s); }

    public void checkoutCommit(String hash) { run(() -> session.git().checkoutDetached(hash)); }

    public void createBranch(String name, String startPoint) {
        run(() -> session.git().createBranch(name, startPoint, false));
    }

    public void createTag(String name, String target) {
        run(() -> session.git().createTag(name, target, null));
    }

    /** A destructive operation that can be reversed, shown in a banner until dismissed or replaced. */
    public record UndoOffer(String message, Runnable action) {}

    private final ObjectProperty<UndoOffer> undoOffer = new SimpleObjectProperty<>();

    public ReadOnlyObjectProperty<UndoOffer> undoOfferProperty() { return undoOffer; }

    public void dismissUndo() { undoOffer.set(null); }

    /** Runs the pending undo action, if any. */
    public void undo() {
        UndoOffer offer = undoOffer.get();
        if (offer == null) return;
        undoOffer.set(null);
        run(offer.action());
    }

    private void offerUndo(String message, Runnable action) {
        Fx.run(() -> undoOffer.set(new UndoOffer(message, action)));
    }

    /** A hard reset also offers to go back to where the branch was before. */
    public void reset(String hash, com.github.gitguiorg.gitgui.git.GitClient.ResetMode mode) {
        com.github.gitguiorg.gitgui.model.RepoStatus s = status.get();
        String old = s == null ? null : s.headOid();
        session.async(() -> { session.git().reset(hash, mode); return null; }).whenComplete((v, err) -> {
            session.notifyChanged();
            if (err != null) { errorHandler.accept(err); return; }
            if (mode == com.github.gitguiorg.gitgui.git.GitClient.ResetMode.HARD
                    && old != null && !old.isBlank() && !old.equals(hash)) {
                offerUndo("Hard reset to " + Hashes.shortHash(hash) + " (was " + Hashes.shortHash(old) + ").",
                        () -> session.git().reset(old, com.github.gitguiorg.gitgui.git.GitClient.ResetMode.HARD));
            }
        });
    }

    /** Applies a patch file; the mailbox form ({@code git am}) can stop with conflicts, shown in the banner. */
    public void applyPatch(java.nio.file.Path file, boolean mailbox) {
        conflictAware("Patch apply", () -> mailbox
                ? session.git().applyMailPatch(file)
                : session.git().applyPatchFile(file));
    }

    /** Writes one patch per commit of the comparison (or of the range since the merge base). */
    public void exportPatches(String a, String b, boolean mergeBase, java.nio.file.Path dir) {
        run(() -> session.git().formatPatchRange(a, b, mergeBase, dir));
    }

    public void merge(String rev) { merge(rev, false, false); }
    public void merge(String rev, boolean noFf) { merge(rev, noFf, false); }
    public void merge(String rev, boolean noFf, boolean squash) {
        conflictAware("Merge", () -> session.git().merge(rev, noFf, squash));
    }
    public void cherryPick(String rev) { conflictAware("Cherry-pick", () -> session.git().cherryPick(rev)); }
    public void revert(String rev) { conflictAware("Revert", () -> session.git().revert(rev)); }

    private void conflictAware(String what, java.util.function.Supplier<com.github.gitguiorg.gitgui.model.MergeOutcome> op) {
        session.async(op::get).whenComplete((outcome, err) -> {
            session.notifyChanged();
            if (err != null) { errorHandler.accept(err); return; }
            if (outcome == com.github.gitguiorg.gitgui.model.MergeOutcome.CONFLICTS) {
                Fx.run(() -> new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.WARNING,
                        what + " stopped with conflicts. Resolve them in the working tree.").show());
            }
        });
    }

    public String statusText() {
        RepoStatus s = status.get();
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.isDetached() ? "detached @ " + Hashes.shortHash(s.headOid()) : s.branch());
        if (s.upstream() != null) sb.append("  ↑").append(s.ahead()).append(" ↓").append(s.behind());
        return sb.toString();
    }

    public void close() {
        network.stop();
        session.close();
    }
}
