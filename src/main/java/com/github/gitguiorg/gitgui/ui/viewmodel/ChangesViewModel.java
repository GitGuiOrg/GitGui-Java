package com.github.gitguiorg.gitgui.ui.viewmodel;

import com.github.gitguiorg.gitgui.git.GitClient;
import com.github.gitguiorg.gitgui.git.GitException;
import com.github.gitguiorg.gitgui.model.FileDiff;
import com.github.gitguiorg.gitgui.model.RepoStatus;
import com.github.gitguiorg.gitgui.model.StatusEntry;
import com.github.gitguiorg.gitgui.ui.Fx;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

/** Working-tree changes (staged/unstaged lists) and the commit form. FX-thread only. */
public final class ChangesViewModel {
    private final RepoViewModel repo;
    private final ObservableList<StatusEntry> unstaged = FXCollections.observableArrayList();
    private final ObservableList<StatusEntry> staged = FXCollections.observableArrayList();
    private final StringProperty message = new SimpleStringProperty("");
    private final BooleanProperty amend = new SimpleBooleanProperty();
    private final BooleanProperty sign = new SimpleBooleanProperty();
    private final BooleanProperty hookFailed = new SimpleBooleanProperty();
    private final ObjectProperty<GitClient.Identity> identity =
            new SimpleObjectProperty<>(new GitClient.Identity("", "", "missing"));
    private final StringProperty author = new SimpleStringProperty("");
    private final StringProperty date = new SimpleStringProperty("");
    private final BooleanProperty showIgnored = new SimpleBooleanProperty();
    private java.util.Set<String> ignoredTracked = java.util.Set.of();
    private boolean active;
    private String selectedPath;
    private boolean selectedStaged;

    public ChangesViewModel(RepoViewModel repo) {
        this.repo = repo;
        amend.addListener((o, was, now) -> { if (now && message.get().isBlank()) prefillLastMessage(); });
        showIgnored.addListener((o, was, now) -> repo.refresh());
        loadCommitDefaults();
    }

    /** Author git would use, the local sign default and the message template; null template when unset. */
    private record CommitDefaults(GitClient.Identity identity, boolean sign, String template) {}

    private void loadCommitDefaults() {
        repo.session().async(() -> {
            GitClient git = repo.session().git();
            return new CommitDefaults(git.identity(), git.signCommitDefault(), git.commitTemplate());
        }).whenComplete((d, err) -> {
            if (err != null) return;   // the commit form still works, it just shows no author
            Fx.run(() -> {
                identity.set(d.identity());
                sign.set(d.sign());
                if (message.get().isBlank() && d.template() != null && !d.template().isBlank()) {
                    message.set(d.template().strip() + "\n");
                }
            });
        });
    }

    /** Opens the file with the system default application. */
    public void openInEditor(String path) { repo.openInEditor(path); }

    /** Re-reads the author shown in the commit form after a local identity change. */
    public void reloadIdentity() {
        repo.session().async(() -> repo.session().git().identity())
                .whenComplete((id, err) -> { if (err == null) Fx.run(() -> identity.set(id)); });
    }

    /** Saves user.name/user.email in the repository config. */
    public void setIdentity(String name, String email) {
        repo.session().mutate(() -> repo.session().git().setIdentity(name, email))
                .whenComplete((v, err) -> {
                    if (err != null) repo.reportError(err); else reloadIdentity();
                });
    }

    public ObjectProperty<GitClient.Identity> identityProperty() { return identity; }

    /** True after a commit failed because a git hook rejected it; the UI then offers to skip the hooks. */
    public BooleanProperty hookFailedProperty() { return hookFailed; }
    public BooleanProperty signProperty() { return sign; }
    public StringProperty authorProperty() { return author; }
    public StringProperty dateProperty() { return date; }

    public ObservableList<StatusEntry> unstaged() { return unstaged; }
    public ObservableList<StatusEntry> staged() { return staged; }
    public StringProperty messageProperty() { return message; }
    public BooleanProperty amendProperty() { return amend; }

    /** Show the ignored files in the unstaged list; they are informational and cannot be staged. */
    public BooleanProperty showIgnoredProperty() { return showIgnored; }

    /** True for a tracked file that matches an ignore rule, i.e. one that can be untracked. */
    public boolean isIgnoredTracked(String path) { return ignoredTracked.contains(path); }
    public String selectedPath() { return selectedPath; }
    public boolean selectedStaged() { return selectedStaged; }

    /** The Changes tab is visible: file selections drive the diff pane. */
    public void setActive(boolean active) { this.active = active; }

    public void update(RepoStatus s, java.util.Set<String> ignoredTracked) {
        this.ignoredTracked = ignoredTracked;
        List<StatusEntry> u = new ArrayList<>();
        List<StatusEntry> st = new ArrayList<>();
        for (StatusEntry e : s.entries()) {
            if (e.kind() == StatusEntry.Kind.IGNORED) {
                if (showIgnored.get()) u.add(e);
                continue;
            }
            if (e.isUnstaged() || e.isConflicted()) u.add(e);
            if (e.isStaged()) st.add(e);
        }
        unstaged.setAll(u);
        staged.setAll(st);
    }

    public void select(StatusEntry e, boolean stagedList) {
        selectedPath = e.path();
        selectedStaged = stagedList;
        if (active) repo.diff().showFile(e, stagedList);
    }

    /** Re-displays the remembered file selection (used when switching to the Changes tab). */
    public void reshowSelection() {
        if (selectedPath == null) return;
        for (StatusEntry e : selectedStaged ? staged : unstaged) {
            if (e.path().equals(selectedPath)) { repo.diff().showFile(e, selectedStaged); return; }
        }
    }

    public void deselect() {
        selectedPath = null;
        if (active) repo.diff().clear();
    }

    // ---- operations

    private static List<String> paths(List<StatusEntry> entries) {
        List<String> p = new ArrayList<>();
        for (StatusEntry e : entries) {
            if (e.kind() == StatusEntry.Kind.IGNORED) continue;   // git refuses to add them without -f
            p.add(e.path());
            if (e.origPath() != null) p.add(e.origPath());
        }
        return p;
    }

    public void stage(List<StatusEntry> entries) {
        List<String> p = paths(entries);
        repo.run(() -> repo.session().git().stage(p));
    }

    public void unstage(List<StatusEntry> entries) {
        List<String> p = paths(entries);
        repo.run(() -> repo.session().git().unstage(p));
    }

    public void stash(String message, boolean includeUntracked) { repo.stash(message, includeUntracked); }

    public javafx.beans.property.ObjectProperty<com.github.gitguiorg.gitgui.model.Operation> operationProperty() { return repo.operationProperty(); }
    public String operationMessage() { return repo.operationMessage(); }
    public void operationContinue() { repo.operationContinue(); }
    public void operationAbort() { repo.operationAbort(); }
    public void openResolver(StatusEntry e) { repo.openResolver(e); }
    public void openBlame(String path, String rev) { repo.openBlame(path, rev); }
    public javafx.beans.property.ReadOnlyObjectProperty<GitClient.BisectState> bisectStateProperty() {
        return repo.bisectStateProperty();
    }
    public void bisectCurrent(boolean good) { repo.bisectCurrent(good); }
    public void bisectSkip() { repo.bisectSkip(); }
    public void openFileHistory(String path) { repo.openFileHistory(path); }

    public void stageAll() { stage(List.copyOf(unstaged)); }
    public void unstageAll() { unstage(List.copyOf(staged)); }

    /** Discards changes: tracked files are restored, untracked files are deleted. Destructive. */
    public void discard(List<StatusEntry> entries) {
        List<String> tracked = new ArrayList<>();
        List<String> untracked = new ArrayList<>();
        for (StatusEntry e : entries) {
            if (e.kind() == StatusEntry.Kind.IGNORED) continue;   // removed with Clean, not discarded
            (e.kind() == StatusEntry.Kind.UNTRACKED ? untracked : tracked).add(e.path());
        }
        if (tracked.isEmpty() && untracked.isEmpty()) return;
        Path root = repo.session().root();
        repo.run(() -> {
            if (!tracked.isEmpty()) repo.session().git().discard(tracked);
            for (String u : untracked) {
                try {
                    deleteUntracked(root.resolve(u));
                } catch (IOException ex) {
                    throw new IllegalStateException("Cannot delete " + u + ": " + ex.getMessage(), ex);
                }
            }
        });
    }

    /**
     * Deletes an untracked file or directory tree. Git reports an untracked folder as a single entry ending in a
     * slash, so a plain delete would fail on every folder that still has files in it.
     */
    static void deleteUntracked(Path target) throws IOException {
        if (!Files.exists(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
        if (!Files.isDirectory(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            Files.deleteIfExists(target);
            return;
        }
        try (var walk = Files.walk(target)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }

    /** @param hunks hunk index → selected line indexes (null = whole hunk) */
    public void stageHunks(FileDiff d, Map<Integer, Set<Integer>> hunks) {
        repo.run(() -> hunks.forEach((h, lines) -> repo.session().git().stageHunk(d, h, lines)));
    }

    public void unstageHunks(FileDiff d, Map<Integer, Set<Integer>> hunks) {
        repo.run(() -> hunks.forEach((h, lines) -> repo.session().git().unstageHunk(d, h, lines)));
    }

    public void discardHunks(FileDiff d, Map<Integer, Set<Integer>> hunks) {
        repo.run(() -> hunks.forEach((h, lines) -> repo.session().git().discardHunk(d, h, lines)));
    }

    /** Appends a rule to .gitignore, or to .git/info/exclude when {@code localOnly}. */
    public void ignore(String pattern, boolean localOnly) {
        repo.run(() -> repo.session().git().ignorePattern(pattern, localOnly));
    }

    /** Stops tracking the given files, keeping them on disk. */
    public void stopTracking(List<StatusEntry> entries) {
        List<String> p = new ArrayList<>();
        for (StatusEntry e : entries) {
            if (e.kind() != StatusEntry.Kind.IGNORED && e.isUnstaged()) p.add(e.path());
        }
        if (!p.isEmpty()) repo.run(() -> repo.session().git().stopTracking(p));
    }

    /** Loads what {@code git clean} would delete, then hands it to {@code onReady} on the FX thread. */
    public void cleanPreview(boolean includeIgnored, java.util.function.Consumer<List<String>> onReady) {
        repo.session().async(() -> repo.session().git().cleanPreview(includeIgnored)).whenComplete((items, err) -> {
            if (err != null) { repo.reportError(err); return; }
            Fx.run(() -> onReady.accept(items));
        });
    }

    /** Deletes untracked files (and ignored ones with {@code includeIgnored}). Destructive. */
    public void clean(List<String> paths, boolean includeIgnored) {
        repo.run(() -> repo.session().git().clean(paths, includeIgnored));
    }

    public void commit() { doCommit(false); }

    /** Commits again with {@code --no-verify}, after the user chose to skip the failing hook. */
    public void commitWithoutHooks() { doCommit(true); }

    /**
     * True when a failed commit should be blamed on a git hook. The presence check runs a git command, so a broken
     * git must not hide the original error: anything it throws means "not a hook failure".
     */
    static boolean isHookFailure(Throwable cause, java.util.function.BooleanSupplier hookPresent) {
        if (cause instanceof GitException g && g.stderr() != null
                && g.stderr().toLowerCase(java.util.Locale.ROOT).contains("hook")) {
            return true;
        }
        try {
            return hookPresent.getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void doCommit(boolean noVerify) {
        hookFailed.set(false);   // a previous failure must not drive the skip-hooks offer
        String msg = message.get();
        boolean am = amend.get();
        boolean sg = sign.get();
        String au = author.get().isBlank() ? null : author.get().strip();
        String dt = date.get().isBlank() ? null : date.get().strip();
        repo.session().mutate(() -> {
            if (am && msg.isBlank()) repo.session().git().amendNoEdit(noVerify);
            else repo.session().git().commit(msg, am, au, dt, sg, noVerify);
        }).whenComplete((v, err) -> {
            if (err != null) {
                if (isHookFailure(Fx.unwrap(err), () -> repo.session().git().commitHookPresent())) {
                    Fx.run(() -> hookFailed.set(true));
                }
                repo.reportError(err);
                return;
            }
            Fx.run(() -> { hookFailed.set(false); message.set(""); amend.set(false); author.set(""); date.set(""); });
        });
    }

    private void prefillLastMessage() {
        repo.session().async(() -> repo.session().git().log(0, 1, false)).whenComplete((cs, err) -> {
            if (err != null || cs.isEmpty()) return;
            var c = cs.get(0);
            Fx.run(() -> {
                if (amend.get() && message.get().isBlank()) {
                    message.set(c.body().isBlank() ? c.subject() : c.subject() + "\n\n" + c.body());
                }
            });
        });
    }
}
