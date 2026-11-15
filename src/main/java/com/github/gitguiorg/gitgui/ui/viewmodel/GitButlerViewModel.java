package com.github.gitguiorg.gitgui.ui.viewmodel;

import com.github.gitguiorg.gitgui.git.ButBranch;
import com.github.gitguiorg.gitgui.git.ButClient;
import com.github.gitguiorg.gitgui.git.ButConflicts;
import com.github.gitguiorg.gitgui.git.ButPullCheck;
import com.github.gitguiorg.gitgui.git.ButStatus;
import com.github.gitguiorg.gitgui.git.CancelToken;
import com.github.gitguiorg.gitgui.model.GitRef;
import com.github.gitguiorg.gitgui.model.RepoStatus;
import com.github.gitguiorg.gitgui.ui.Fx;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;

/** GitButler workspace status for one repository, loaded only when its tab is selected. FX-thread only. */
public final class GitButlerViewModel {
    private final RepoViewModel repo;
    private final ButClient client;
    private final String problem;
    private final ReadOnlyObjectWrapper<ButStatus> status = new ReadOnlyObjectWrapper<>();
    private final ReadOnlyStringWrapper rawOutput = new ReadOnlyStringWrapper();
    private final ReadOnlyStringWrapper warning = new ReadOnlyStringWrapper();
    private final ReadOnlyStringWrapper error = new ReadOnlyStringWrapper();
    private final ReadOnlyObjectWrapper<List<ButBranch>> unappliedBranches = new ReadOnlyObjectWrapper<>(List.of());
    private final ReadOnlyStringWrapper branchWarning = new ReadOnlyStringWrapper();
    private final ReadOnlyStringWrapper actionMessage = new ReadOnlyStringWrapper();
    private final ReadOnlyBooleanWrapper loading = new ReadOnlyBooleanWrapper();
    private final ReadOnlyBooleanWrapper actionBusy = new ReadOnlyBooleanWrapper();
    private final ReadOnlyObjectWrapper<LocalDateTime> updatedAt = new ReadOnlyObjectWrapper<>();
    private RepoStatus lastStatus;
    private List<GitRef> lastRefs = List.of();
    private boolean active;
    private boolean pending;
    private CancelToken loadToken;
    private CancelToken actionToken;

    private final java.util.Map<String, Integer> commitStacks = new java.util.HashMap<>();
    private final javafx.beans.property.ReadOnlyIntegerWrapper stackTick = new javafx.beans.property.ReadOnlyIntegerWrapper();

    /** The repository this model belongs to. */
    public RepoViewModel repo() { return repo; }

    /** Changes whenever the commit-to-stack mapping is rebuilt, so views can repaint their stack tags. */
    public javafx.beans.property.ReadOnlyIntegerProperty stackTickProperty() { return stackTick.getReadOnlyProperty(); }

    /** 1-based stack number of a commit (full id), or 0 when it is in no applied stack. */
    public int stackOf(String commitId) { return commitId == null ? 0 : commitStacks.getOrDefault(commitId, 0); }

    /** Names of the applied branches, in stack order (top of each stack first). */
    public List<String> appliedBranchNames() {
        ButStatus s = status.get();
        if (s == null) return List.of();
        return s.stacks().stream().flatMap(st -> st.branches().stream()).map(ButStatus.Branch::name).toList();
    }

    private void rebuildStacks(ButStatus s) {
        commitStacks.clear();
        if (s != null) {
            int n = 0;
            for (ButStatus.Stack st : s.stacks()) {
                n++;
                for (ButStatus.Branch b : st.branches()) {
                    for (ButStatus.Commit c : b.commits()) if (c.id() != null) commitStacks.put(c.id(), n);
                }
            }
        }
        stackTick.set(stackTick.get() + 1);
    }

    public GitButlerViewModel(RepoViewModel repo) {
        this.repo = repo;
        ButClient found = null;
        String problem = null;
        try {
            found = ButClient.find(repo.root(), repo.settings().butPath);
            if (found == null) {
                problem = "but.exe not found. Install GitButler or set its path in Settings.";
            }
        } catch (RuntimeException e) {
            problem = e.getMessage();
        }
        this.client = found;
        this.problem = problem;
        if (problem != null) error.set(problem);
        status.addListener((o, a, s) -> rebuildStacks(s));
        lastStatus = repo.statusProperty().get();
        lastRefs = List.copyOf(repo.refs());
        repo.statusProperty().addListener((o, a, b) -> reloadWhenRepoChanged());
        repo.refs().addListener((javafx.collections.ListChangeListener<GitRef>) c -> reloadWhenRepoChanged());
    }

    /** Starts following repository changes and refreshes, now that the tab is selected. */
    public void activate() {
        boolean was = active;
        active = true;
        // Already following the repository and holding a status: nothing to reload.
        if (!was || status.get() == null) load();
    }

    /** Stops automatic reloads; another tab is selected or this tab is closed. */
    public void deactivate() {
        active = false;
        pending = false;
        CancelToken load = loadToken;
        if (load != null) load.cancel();
        CancelToken action = actionToken;
        if (action != null) action.cancel();
    }

    /** Reloads only when status or refs actually changed, not on every repository refresh. */
    private void reloadWhenRepoChanged() {
        RepoStatus status = repo.statusProperty().get();
        List<GitRef> refs = List.copyOf(repo.refs());
        if (Objects.equals(status, lastStatus) && refs.equals(lastRefs)) return;
        lastStatus = status;
        lastRefs = refs;
        if (active) load();
    }

    /** Parsed workspace state, or null while loading, after an error, or when the JSON cannot be read. */
    public ReadOnlyObjectProperty<ButStatus> statusProperty() { return status.getReadOnlyProperty(); }

    /** The JSON printed by but; shown in a collapsible pane and used as fallback when parsing fails. */
    public ReadOnlyStringProperty rawOutputProperty() { return rawOutput.getReadOnlyProperty(); }

    /** Why the structured view could not be built; empty when the parse succeeded. */
    public ReadOnlyStringProperty warningProperty() { return warning.getReadOnlyProperty(); }

    /** Why but itself could not be run; empty when the last load reached but. */
    public ReadOnlyStringProperty errorProperty() { return error.getReadOnlyProperty(); }

    /** Branches known to but that are not currently applied to the workspace. */
    public ReadOnlyObjectProperty<List<ButBranch>> unappliedBranchesProperty() {
        return unappliedBranches.getReadOnlyProperty();
    }

    /** Why the unapplied branch list could not be read; empty when it was read or not requested. */
    public ReadOnlyStringProperty branchWarningProperty() { return branchWarning.getReadOnlyProperty(); }

    /** Short confirmation of the last completed action; empty while none is shown. */
    public ReadOnlyStringProperty actionMessageProperty() { return actionMessage.getReadOnlyProperty(); }

    /** Dismisses the confirmation banner without starting another action. */
    public void clearActionMessage() { actionMessage.set(""); }

    public ReadOnlyBooleanProperty loadingProperty() { return loading.getReadOnlyProperty(); }

    /** True while an apply/unapply action is running. */
    public ReadOnlyBooleanProperty actionBusyProperty() { return actionBusy.getReadOnlyProperty(); }

    /** Local time of the last {@code but status} run, or null before the first one. */
    public ReadOnlyObjectProperty<LocalDateTime> updatedAtProperty() { return updatedAt.getReadOnlyProperty(); }

    /** Path of the but executable, or an empty string when it was not found. */
    public String executable() { return client == null ? "" : client.path(); }

    /** Opens a workspace-relative file with the system default application. */
    public void openFile(String path) {
        if (path != null && !path.isBlank()) repo.openInEditor(path);
    }

    /** Absolute path of a workspace-relative file, or an empty string when the path is missing. */
    public String absolutePath(String path) {
        if (path == null || path.isBlank()) return "";
        return repo.root().resolve(path).toAbsolutePath().normalize().toString();
    }

    /** Runs {@code but --json status}; the first run may create {@code .git/gitbutler} in the repository. */
    public void load() {
        if (client == null) {
            status.set(null);
            unappliedBranches.set(List.of());
            branchWarning.set("");
            error.set(problem == null ? "but.exe not found." : problem);
            return;
        }
        if (loading.get()) {
            pending = true;
            return;
        }
        CancelToken token = new CancelToken();
        loadToken = token;
        loading.set(true);
        repo.session().async(() -> {
            ButClient.WorkspaceStatus workspace = client.status(token);
            List<ButBranch> branches = List.of();
            String branchesProblem = "";
            try {
                branches = client.unappliedBranches(token);
            } catch (RuntimeException e) {
                branchesProblem = message(e);
            }
            return new Loaded(workspace, branches, branchesProblem);
        }).whenComplete((loaded, err) -> Fx.run(() -> {
            if (loadToken == token) loadToken = null;
            loading.set(false);
            if (token.isCancelled()) {
                loadIfPending();
                return;
            }
            if (err != null) {
                status.set(null);
                rawOutput.set("");
                warning.set("");
                unappliedBranches.set(List.of());
                branchWarning.set("");
                error.set(message(err));
            } else {
                error.set("");
                rawOutput.set(loaded.workspace().rawJson());
                status.set(loaded.workspace().status());
                warning.set(loaded.workspace().status() == null ? loaded.workspace().warning() : "");
                unappliedBranches.set(loaded.branches());
                branchWarning.set(loaded.branchWarning());
            }
            updatedAt.set(LocalDateTime.now());
            loadIfPending();
        }));
    }

    /** Applies an unapplied branch to the GitButler workspace. */
    public void applyBranch(String branch) {
        runAction("Branch applied: " + branch + ".", token -> client.applyBranch(branch, token));
    }

    /** Removes an applied branch from the workspace without deleting it. */
    public void unapplyBranch(String branch) {
        runAction("Branch removed from the workspace: " + branch + ".",
                token -> client.unapplyBranch(branch, token));
    }

    /** Creates a new applied branch without stacking it. */
    public void createBranch(String name) {
        runAction("Branch created: " + name + ".", token -> client.createBranch(name, token));
    }

    /** Creates a new applied branch stacked directly above {@code above}. */
    public void createBranchAbove(String name, String above) {
        runAction("Stacked branch created: " + name + ".",
                token -> client.createBranchAbove(name, above, token));
    }

    /** Creates a new applied branch stacked directly below {@code below}. */
    public void createBranchBelow(String name, String below) {
        runAction("Stacked branch created: " + name + ".",
                token -> client.createBranchBelow(name, below, token));
    }

    /** Renames an applied branch without changing its commits or stack position. */
    public void renameBranch(String branch, String newName) {
        runAction("Branch renamed to " + newName + ".",
                token -> client.renameBranch(branch, newName, token));
    }

    /** Restacks an applied branch directly above another branch. */
    public void moveBranchAbove(String branch, String above) {
        runAction("Branch moved above " + above + ".",
                token -> client.moveBranchAbove(branch, above, token));
    }

    /** Commits all uncommitted changes to an applied branch. */
    public void commitChanges(String branch, String message) {
        runAction("Changes committed to " + branch + ".",
                token -> client.commitChanges(branch, message, token));
    }

    /** Commits only one changed path to an applied branch. */
    public void commitChanges(String branch, String message, String path) {
        runAction("Changes committed to " + branch + ".",
                token -> client.commitChanges(branch, message, path, token));
    }

    /** Commits the selected changed paths to an applied branch. */
    public void commitChanges(String branch, String message, List<String> paths) {
        runAction("Changes committed to " + branch + ".",
                token -> client.commitChanges(branch, message, paths, token));
    }

    /** Commits the given paths to an applied branch and runs {@code onSuccess} once it worked. */
    public void commitChanges(String branch, String message, List<String> paths, Runnable onSuccess) {
        runAction("Changes committed to " + branch + ".",
                token -> client.commitChanges(branch, message, paths, token), onSuccess);
    }

    /** Moves commits above or below another commit (reorder, also across branches). */
    public void moveCommits(List<String> commits, String target, boolean above) {
        runAction(commits.size() == 1 ? "Commit moved." : commits.size() + " commits moved.",
                token -> client.moveCommits(commits, target, above, token));
    }

    /** Moves commits to the tip of another applied branch. */
    public void moveCommitsToBranch(List<String> commits, String branch) {
        runAction((commits.size() == 1 ? "Commit" : commits.size() + " commits") + " moved to " + branch + ".",
                token -> client.moveCommitsToBranch(commits, branch, token));
    }

    /** Makes a stacked branch independent. */
    public void unstackBranch(String branch) {
        runAction("Branch " + branch + " is now independent.", token -> client.unstackBranch(branch, token));
    }

    /** Amends uncommitted files into a commit. */
    public void amendChanges(List<String> paths, String commit) {
        runAction("Changes added to the commit.", token -> client.amendChanges(paths, commit, token));
    }

    /** Squashes commits into a target commit, keeping the target's message. */
    public void squashCommits(List<String> sources, String target) {
        runAction("Commits squashed.", token -> client.squashCommits(sources, target, token));
    }

    /** Replaces a commit message, restacking any dependent commits. */
    public void rewordCommit(String commit, String message) {
        runAction("Commit message updated.", token -> client.rewordCommit(commit, message, token));
    }

    /** Moves a commit to the tip of another applied branch. */
    public void moveCommitToBranch(String commit, String branch) {
        runAction("Commit moved to " + branch + ".",
                token -> client.moveCommitToBranch(commit, branch, token));
    }

    /** Discards the uncommitted changes at {@code path}. */
    public void discardChange(String path) {
        runAction("Change discarded.", token -> client.discardChange(path, token));
    }

    /** Discards the selected uncommitted changes. */
    public void discardChanges(List<String> paths) {
        runAction(paths.size() == 1 ? "Change discarded." : paths.size() + " changes discarded.",
                token -> client.discardChanges(paths, token));
    }

    /** Moves a commit back to the uncommitted changes. */
    public void uncommit(String commit) {
        runAction("Commit moved back to uncommitted changes.", token -> client.uncommit(commit, token));
    }

    /** Checks the remote changes before updating the workspace. */
    public void checkPull(Consumer<ButPullCheck> result) {
        if (client == null || actionBusy.get()) return;
        actionBusy.set(true);
        CancelToken token = new CancelToken();
        actionToken = token;
        repo.session().async(() -> client.checkPullWorkspace(token)).whenComplete((check, err) -> Fx.run(() -> {
            if (actionToken == token) actionToken = null;
            actionBusy.set(false);
            if (token.isCancelled()) return;
            if (err != null) {
                repo.reportError(Fx.unwrap(err));
                return;
            }
            result.accept(check);
        }));
    }

    /** Fetches the latest target and rebases applied branches onto it. */
    public void pullWorkspace() {
        runAction("Workspace updated from remote.", client::pullWorkspace);
    }

    /** Loads the conflicts of the oldest conflicted commit. */
    public void loadConflicts(Consumer<ButConflicts> result) {
        if (client == null || actionBusy.get()) return;
        actionBusy.set(true);
        CancelToken token = new CancelToken();
        actionToken = token;
        repo.session().async(() -> client.conflicts(token)).whenComplete((conflicts, err) -> Fx.run(() -> {
            if (actionToken == token) actionToken = null;
            actionBusy.set(false);
            if (token.isCancelled()) return;
            if (err != null) {
                repo.reportError(Fx.unwrap(err));
                return;
            }
            result.accept(conflicts);
        }));
    }

    /** Resolves the chosen side of every listed hunk, then reloads the workspace. */
    public void resolveConflicts(String branch, List<ConflictChoice> choices) {
        if (choices.isEmpty()) return;
        // Resolving a hunk renumbers the remaining ones, so work each file from the last hunk backwards.
        List<ConflictChoice> ordered = choices.stream()
                .sorted(Comparator.comparing(ConflictChoice::path)
                        .thenComparing(Comparator.comparingInt(ConflictChoice::conflict).reversed()))
                .toList();
        runAction("Selected conflict hunks resolved.", token -> {
            for (ConflictChoice choice : ordered) {
                client.resolveConflictHunk(branch, choice.path(), choice.conflict(), choice.ours(), token);
            }
        });
    }

    /** The side to keep for one conflicted hunk: remote (ours) or local commit (theirs). */
    public record ConflictChoice(String path, int conflict, boolean ours) {}

    /** Pushes a branch or its stack to the remote. */
    public void pushBranch(String branch) {
        runAction("Pushed " + branch + " to remote.", token -> client.pushBranch(branch, token));
    }

    /** Pushes every unpushed commit in the stack containing {@code branch}. */
    public void pushStack(String branch) {
        runAction("Stack pushed to remote.", token -> client.pushStack(branch, token));
    }

    /** Integrates the remote counterpart into a local branch. */
    public void updateBranch(String branch) {
        runAction("Branch updated from remote: " + branch + ".",
                token -> client.updateBranch(branch, token));
    }

    private void runAction(String successMessage, Consumer<CancelToken> action) {
        runAction(successMessage, action, null);
    }

    private void runAction(String successMessage, Consumer<CancelToken> action, Runnable onSuccess) {
        if (client == null || actionBusy.get()) return;
        actionMessage.set("");
        actionBusy.set(true);
        CancelToken token = new CancelToken();
        actionToken = token;
        repo.session().async(() -> {
            action.accept(token);
            return null;
        }).whenComplete((v, err) -> Fx.run(() -> {
            if (actionToken == token) actionToken = null;
            actionBusy.set(false);
            if (token.isCancelled()) return;
            if (err != null) {
                repo.reportError(Fx.unwrap(err));
                return;
            }
            actionMessage.set(successMessage);
            if (onSuccess != null) onSuccess.run();
            repo.session().notifyChanged();
            load();
        }));
    }

    private void loadIfPending() {
        if (!pending) return;
        pending = false;
        if (active) load();
    }

    private static String message(Throwable err) {
        Throwable cause = err instanceof CompletionException && err.getCause() != null ? err.getCause() : err;
        return cause.getMessage() == null ? cause.toString() : cause.getMessage();
    }

    private record Loaded(ButClient.WorkspaceStatus workspace, List<ButBranch> branches, String branchWarning) {}
}
