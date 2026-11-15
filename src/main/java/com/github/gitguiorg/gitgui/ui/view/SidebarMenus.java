package com.github.gitguiorg.gitgui.ui.view;

import com.github.gitguiorg.gitgui.model.GitRef;
import com.github.gitguiorg.gitgui.model.StashEntry;
import com.github.gitguiorg.gitgui.model.SubmoduleInfo;
import com.github.gitguiorg.gitgui.model.WorktreeInfo;
import com.github.gitguiorg.gitgui.ui.viewmodel.RepoViewModel;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;

/** Context menus of the sidebar entries and the double-click action. */
final class SidebarMenus {
    private final RepoViewModel vm;

    SidebarMenus(RepoViewModel vm) {
        this.vm = vm;
    }

    ContextMenu forNode(SidebarView.Node n) {
        return switch (n.kind()) {
            case BRANCHES -> new ContextMenu(mi("New branch…", this::newBranchFromHead));
            case BUT_BRANCH -> new ContextMenu(mi("Apply to GitButler workspace", () -> vm.gitButler().applyBranch(n.label())));
            case REMOTES -> new ContextMenu(mi("Add remote…", this::addRemote));
            case TAGS -> new ContextMenu(mi("New tag…", this::newTagAtHead));
            case REMOTE -> remoteMenu(n.remote());
            case STASH -> stashMenu(n.stash());
            case SUBMODULES -> new ContextMenu(
                    mi("Init / update all submodules", () -> vm.updateSubmodules(List.of())),
                    mi("Init / update all recursively", () -> vm.updateSubmodulesRecursively(List.of())),
                    mi("Add submodule…", this::addSubmodule),
                    new SeparatorMenuItem(),
                    submoduleRecurseItem());
            case SUBMODULE -> submoduleMenu((SubmoduleInfo) n.data());
            case WORKTREES -> new ContextMenu(
                    mi("Add worktree…", this::addWorktree),
                    mi("Prune missing worktrees", () -> {
                        if (Dialogs.confirm("Prune worktrees", "Forget the worktrees whose folder no longer exists?")) {
                            vm.pruneWorktrees();
                        }
                    }));
            case WORKTREE -> worktreeMenu((WorktreeInfo) n.data());
            case REF -> switch (n.ref().type()) {
                case LOCAL_BRANCH -> vm.isButWorkspace() ? butLocalMenu(n.ref()) : localMenu(n.ref());
                case REMOTE_BRANCH -> remoteBranchMenu(n.ref());
                case TAG -> tagMenu(n.ref());
            };
            default -> null;
        };
    }

    /** Double-click on an entry: checkout a ref, or open a submodule/worktree in a new tab. */
    void open(SidebarView.Node n) {
        if (vm.isButWorkspace() && (n.kind() == SidebarView.Kind.BUT_BRANCH
                || (n.ref() != null && n.ref().type() == GitRef.Type.LOCAL_BRANCH))) {
            // Checking out would leave the workspace; double-click applies the branch instead.
            String name = n.kind() == SidebarView.Kind.BUT_BRANCH ? n.label() : n.ref().shortName();
            if (!vm.gitButler().appliedBranchNames().contains(name)) vm.gitButler().applyBranch(name);
            return;
        }
        if (n.ref() != null) {
            vm.checkout(n.ref());
        } else if (n.data() instanceof SubmoduleInfo s) {
            if (s.state() != SubmoduleInfo.State.UNINITIALIZED) vm.openRepo(vm.root().resolve(s.path()));
        } else if (n.data() instanceof WorktreeInfo w) {
            if (!isCurrent(w)) vm.openRepo(Path.of(w.path()));
        }
    }

    private static MenuItem mi(String text, Runnable action) {
        MenuItem m = new MenuItem(text);
        m.setOnAction(e -> action.run());
        return m;
    }

    private static MenuItem mi(String text, boolean disabled, Runnable action) {
        MenuItem m = mi(text, action);
        m.setDisable(disabled);
        return m;
    }

    static String remoteOf(RepoViewModel vm, GitRef r) {
        return GitRef.remoteOf(r.shortName(), vm.remoteNames());
    }

    private String remoteOf(GitRef r) {
        return remoteOf(vm, r);
    }

    private void newBranchFromHead() {
        Dialogs.askBranch("HEAD").ifPresent(r -> vm.createBranch(r.name(), "HEAD", r.checkout()));
    }

    private void newTagAtHead() {
        Dialogs.askTag().ifPresent(t -> vm.createTag(t[0], "HEAD", t[1].isEmpty() ? null : t[1]));
    }

    private void addRemote() {
        Dialogs.askNameUrl("Add remote", "", "").ifPresent(v -> vm.addRemote(v[0], v[1]));
    }

    /** One remote: used directly, or chosen from a dialog when there are several. */
    private Optional<String> chooseRemote(String header) {
        List<String> names = List.copyOf(vm.remoteNames());
        if (names.isEmpty()) {
            vm.reportError(new IllegalStateException("No remote is configured for this repository."));
            return Optional.empty();
        }
        if (names.size() == 1) return Optional.of(names.get(0));
        return Dialogs.choose("Remote", header, names);
    }

    /** Local branch menu in a GitButler workspace: apply/unapply first, and no checkout (it would leave the workspace). */
    private ContextMenu butLocalMenu(GitRef r) {
        String name = r.shortName();
        boolean applied = vm.gitButler().appliedBranchNames().contains(name);
        ContextMenu m = new ContextMenu(
                applied ? mi("Unapply from workspace", () -> vm.gitButler().unapplyBranch(name))
                        : mi("Apply to workspace", () -> vm.gitButler().applyBranch(name)),
                new SeparatorMenuItem(),
                compareMenu(name),
                mi("Push…", () -> pushBranch(r)));
        return m;
    }

    private ContextMenu localMenu(GitRef r) {
        String name = r.shortName();
        boolean head = r.head();
        ContextMenu m = new ContextMenu(
                mi("Checkout", head, () -> vm.checkout(r)),
                mi("Merge into current branch", head, () -> vm.merge(name)),
                mi("Rebase current branch onto this", head, () -> vm.rebaseOnto(name)),
                compareMenu(name),
                new SeparatorMenuItem(),
                mi("Push…", () -> pushBranch(r)),
                mi("Set upstream…", () -> setUpstream(r)));
        if (r.upstream() != null) m.getItems().add(mi("Unset upstream (" + r.upstream() + ")", () -> vm.unsetUpstream(name)));
        m.getItems().addAll(new SeparatorMenuItem(),
                mi("Rename…", () -> Dialogs.ask("Rename branch", "New name for " + name, name)
                        .filter(n -> !n.equals(name)).ifPresent(n -> vm.renameBranch(name, n))),
                mi("Delete…", head, () -> {
                    if (Dialogs.confirm("Delete branch", "Delete branch " + name + "?")) {
                        vm.tryDeleteBranch(name, msg -> {
                            if (Dialogs.confirm("Branch not fully merged", name + " has commits that are not merged anywhere.\n"
                                    + "Delete it anyway? Those commits may become unreachable.")) {
                                vm.deleteBranch(name, true, r.hash());
                            }
                        });
                    }
                }));
        return m;
    }

    private void pushBranch(GitRef r) {
        String remote;
        String branch;
        boolean setUp;
        if (r.upstream() != null && r.upstream().contains("/")) {
            // Push to the upstream branch, which may have another name than the local one.
            remote = GitRef.remoteOf(r.upstream(), vm.remoteNames());
            branch = GitRef.pushRefspec(r.shortName(), r.upstream(), vm.remoteNames());
            setUp = false;
        } else {
            Optional<String> chosen = chooseRemote("Push " + r.shortName() + " to which remote?");
            if (chosen.isEmpty()) return;
            remote = chosen.get();
            branch = r.shortName();
            setUp = true;
        }
        vm.network().execute("Pushing…", (git, progress, token) ->
                git.push(remote, branch, setUp, false, progress, token));
    }

    private void setUpstream(GitRef r) {
        List<String> candidates = new ArrayList<>();
        for (GitRef x : vm.refs()) if (x.type() == GitRef.Type.REMOTE_BRANCH) candidates.add(x.shortName());
        Dialogs.choose("Set upstream", "Upstream for " + r.shortName(), candidates)
                .ifPresent(up -> vm.setUpstream(r.shortName(), up));
    }

    private ContextMenu remoteBranchMenu(GitRef r) {
        String remote = remoteOf(r);
        String branch = GitRef.branchOf(r.shortName(), remote);
        return new ContextMenu(
                mi("Checkout (create local branch)", () -> vm.checkout(r)),
                mi("Merge into current branch", () -> vm.merge(r.shortName())),
                compareMenu(r.shortName()),
                new SeparatorMenuItem(),
                mi("Delete from remote…", () -> {
                    if (Dialogs.confirm("Delete remote branch", "Delete branch " + branch + " from " + remote + "?")) {
                        vm.network().execute("Deleting remote branch…", (git, progress, token) ->
                                git.deleteRemoteBranch(remote, branch, progress, token));
                    }
                }));
    }

    private ContextMenu tagMenu(GitRef r) {
        String tag = r.shortName();
        return new ContextMenu(
                mi("Checkout (detached)", () -> vm.checkoutCommit(tag)),
                compareMenu(tag),
                new SeparatorMenuItem(),
                mi("Push tag…", () -> chooseRemote("Push tag " + tag + " to which remote?").ifPresent(remote ->
                        vm.network().execute("Pushing tag…", (git, progress, token) -> git.pushTag(remote, tag, progress, token)))),
                mi("Delete tag…", () -> {
                    if (Dialogs.confirm("Delete tag", "Delete tag " + tag + " locally?")) vm.deleteTag(tag);
                }),
                mi("Delete tag from remote…", () -> chooseRemote("Delete tag " + tag + " from which remote?").ifPresent(remote -> {
                    if (Dialogs.confirm("Delete remote tag", "Delete tag " + tag + " from " + remote + "?")) {
                        vm.network().execute("Deleting remote tag…", (git, progress, token) ->
                                git.deleteRemoteTag(remote, tag, progress, token));
                    }
                })));
    }

    /** Compare a revision with the working tree or with HEAD. */
    private Menu compareMenu(String rev) {
        return new Menu("Compare", null,
                mi("With working tree", () -> vm.openCompare(rev, null)),
                mi("With HEAD", () -> vm.openCompare(rev, "HEAD")));
    }

    private ContextMenu remoteMenu(String name) {
        return new ContextMenu(
                mi("Fetch", () -> vm.network().execute("Fetching…", (git, progress, token) ->
                        git.fetch(name, true, progress, token))),
                new SeparatorMenuItem(),
                mi("Edit URL…", () -> Dialogs.ask("Edit remote URL", "URL of " + name, vm.remoteUrls().getOrDefault(name, ""))
                        .ifPresent(u -> vm.setRemoteUrl(name, u))),
                mi("Rename…", () -> Dialogs.ask("Rename remote", "New name for " + name, name)
                        .filter(n -> !n.equals(name)).ifPresent(n -> vm.renameRemote(name, n))),
                mi("Remove…", () -> {
                    if (Dialogs.confirm("Remove remote", "Remove remote " + name + " and its remote-tracking branches?")) {
                        vm.removeRemote(name);
                    }
                }));
    }

    static boolean isCurrent(RepoViewModel vm, WorktreeInfo w) {
        try {
            return Files.isSameFile(Path.of(w.path()), vm.root());
        } catch (java.io.IOException | RuntimeException e) {
            return false;
        }
    }

    private boolean isCurrent(WorktreeInfo w) {
        return isCurrent(vm, w);
    }

    private ContextMenu submoduleMenu(SubmoduleInfo s) {
        return new ContextMenu(
                mi("Open in new tab", s.state() == SubmoduleInfo.State.UNINITIALIZED, () -> vm.openRepo(vm.root().resolve(s.path()))),
                mi(s.state() == SubmoduleInfo.State.UNINITIALIZED ? "Init and update" : "Update to recorded commit",
                        () -> vm.updateSubmodules(List.of(s.path()))),
                mi("Init and update recursively", () -> vm.updateSubmodulesRecursively(List.of(s.path()))),
                new SeparatorMenuItem(),
                mi("Remove…", () -> {
                    if (Dialogs.confirm("Remove submodule",
                            "Remove the submodule at " + s.path() + "?\nThe folder is emptied and the change is staged; the history of the submodule stays in .git/modules.")) {
                        vm.removeSubmodule(s.path());
                    }
                }));
    }

    private void addSubmodule() {
        Dialogs.askSubmodule().ifPresent(v -> vm.addSubmodule(v[0], v[1]));
    }

    /** Repository config: update the submodules after pull and checkout. */
    private CheckMenuItem submoduleRecurseItem() {
        CheckMenuItem item = new CheckMenuItem("Update submodules on pull and checkout");
        item.selectedProperty().bindBidirectional(vm.submoduleRecurseProperty());
        item.setOnAction(e -> vm.setSubmoduleRecurse(item.isSelected()));
        return item;
    }

    private void addWorktree() {
        Set<String> used = new HashSet<>();
        for (WorktreeInfo w : vm.worktrees()) if (w.branch() != null) used.add(w.branch());
        WorktreeDialog.show(vm.root(), vm.refs(), used)
                .ifPresent(r -> vm.addWorktree(r.path(), r.newBranch(), r.startPoint()));
    }

    private ContextMenu worktreeMenu(WorktreeInfo w) {
        boolean current = isCurrent(w);
        boolean missing = w.prunable();
        Path p = Path.of(w.path());
        return new ContextMenu(
                mi("Open in new tab", current || missing, () -> vm.openRepo(p)),
                new SeparatorMenuItem(),
                mi(w.locked() ? "Unlock" : "Lock…", current || w.main() || missing, () -> {
                    if (w.locked()) {
                        vm.unlockWorktree(p);
                    } else {
                        Dialogs.askOptional("Lock worktree", "Reason for locking " + w.path() + " (optional)", "")
                                .ifPresent(reason -> vm.lockWorktree(p, reason));
                    }
                }),
                mi("Move…", current || w.main() || missing, () -> Dialogs.ask("Move worktree", "New folder for " + w.path(), w.path())
                        .ifPresent(dest -> {
                            try {
                                vm.moveWorktree(p, Path.of(dest));
                            } catch (InvalidPathException e) {
                                vm.reportError(e);
                            }
                        })),
                new SeparatorMenuItem(),
                mi("Remove…", current || w.main(), () -> {
                    if (!Dialogs.confirm("Remove worktree", "Remove the worktree " + w.path() + "?")) return;
                    vm.tryRemoveWorktree(p, msg -> {
                        if (Dialogs.confirm("Worktree has local changes",
                                "Git refused to remove " + w.path() + ":\n" + msg + "\n\nForce remove and lose those changes?")) {
                            vm.removeWorktree(p, true);
                        }
                    });
                }),
                mi("Force remove…", current || w.main(), () -> {
                    if (Dialogs.confirm("Force remove worktree", "Remove " + w.path() + " even if it has uncommitted changes?\nThose changes are lost.")) {
                        vm.removeWorktree(p, true);
                    }
                }));
    }

    private ContextMenu stashMenu(StashEntry s) {
        return new ContextMenu(
                mi("Apply", () -> vm.stashApply(s.ref())),
                mi("Pop", () -> vm.stashPop(s.ref())),
                mi("Drop…", () -> {
                    if (Dialogs.confirm("Drop stash", "Drop " + s.ref() + "?\nYou can undo it from the banner at the bottom of the tab.")) {
                        vm.stashDrop(s.ref(), s);
                    }
                }));
    }
}
