package com.github.gitguiorg.gitgui.ui.view;

import com.github.gitguiorg.gitgui.git.ButStatus;
import com.github.gitguiorg.gitgui.model.GitRef;
import com.github.gitguiorg.gitgui.model.StashEntry;
import com.github.gitguiorg.gitgui.model.SubmoduleInfo;
import com.github.gitguiorg.gitgui.model.WorktreeInfo;
import com.github.gitguiorg.gitgui.ui.viewmodel.RepoViewModel;
import com.github.gitguiorg.gitgui.util.RemoteUrls;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javafx.collections.ListChangeListener;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.TreeCell;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.MouseButton;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import org.kordamp.ikonli.javafx.FontIcon;

/** Branches, remotes, tags and stashes, with context menus to manage them. */
public final class SidebarView extends TreeView<SidebarView.Node> {
    enum Kind { GROUP, BUT_BRANCH, BRANCHES, REMOTES, TAGS, STASHES, SUBMODULES, WORKTREES, SUBMODULE, WORKTREE, REMOTE, REF, STASH, ROOT }

    record Node(Kind kind, String label, GitRef ref, String icon, StashEntry stash, String remote, Object data, String color) {
        Node(Kind kind, String label, GitRef ref, String icon, StashEntry stash, String remote, Object data) {
            this(kind, label, ref, icon, stash, remote, data, null);
        }

        Node(Kind kind, String label, GitRef ref, String icon, StashEntry stash, String remote) {
            this(kind, label, ref, icon, stash, remote, null, null);
        }

        @Override public String toString() { return label; }
    }

    private final RepoViewModel vm;
    private final SidebarMenus menus;
    /** True while the tree is rebuilt and the saved selection is put back, so selecting does not reload diffs. */
    private boolean restoring;

    private final Kind section;

    public SidebarView(RepoViewModel vm) {
        this(vm, null);
    }

    /** Shows only one group (Branches, Remotes, ...) as a flat tree, or all of them when {@code section} is null. */
    public SidebarView(RepoViewModel vm, Kind section) {
        this.section = section;
        this.vm = vm;
        this.menus = new SidebarMenus(vm);
        setShowRoot(false);
        setCellFactory(tv -> new RefCell());
        vm.refs().addListener((ListChangeListener<GitRef>) c -> scheduleRebuild());
        vm.stashes().addListener((ListChangeListener<StashEntry>) c -> scheduleRebuild());
        vm.butWorkspaceProperty().addListener((o, a, b) -> scheduleRebuild());
        vm.gitButler().statusProperty().addListener((o, a, b) -> scheduleRebuild());
        vm.gitButler().unappliedBranchesProperty().addListener((o, a, b) -> scheduleRebuild());
        vm.remoteNames().addListener((ListChangeListener<String>) c -> scheduleRebuild());
        vm.statusProperty().addListener((o, a, b) -> scheduleRebuild());
        getSelectionModel().selectedItemProperty().addListener((o, a, item) -> {
            if (restoring) return;
            if (item != null && item.getValue().stash() != null) vm.showStash(item.getValue().stash());
        });
        setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                TreeItem<Node> item = getSelectionModel().getSelectedItem();
                if (item != null && item.getValue().ref() != null && !vm.isButWorkspace()) vm.checkout(item.getValue().ref());
                if (item != null) menus.open(item.getValue());
            }
        });
        rebuild();
    }

    // ------------------------------------------------------------ tree

    private static TreeItem<Node> group(Kind kind, String label, String icon) {
        return new TreeItem<>(new Node(kind, label, null, icon, null, null));
    }

    private static TreeItem<Node> group(Kind kind, String label, String icon, String color) {
        return new TreeItem<>(new Node(kind, label, null, icon, null, null, null, color));
    }

    private static TreeItem<Node> leaf(GitRef ref, String label, String icon) {
        return new TreeItem<>(new Node(Kind.REF, label, ref, icon, null, null));
    }

    private static TreeItem<Node> leaf(GitRef ref, String label, String icon, String color) {
        return new TreeItem<>(new Node(Kind.REF, label, ref, icon, null, null, null, color));
    }

    private static TreeItem<Node> remoteGroup(String name) {
        return new TreeItem<>(new Node(Kind.REMOTE, name, null, "mdi2c-cloud-outline", null, name));
    }

    private boolean rebuildPending;

    /** Coalesces the several list updates of one refresh into a single tree rebuild. */
    private void scheduleRebuild() {
        if (rebuildPending) return;
        rebuildPending = true;
        javafx.application.Platform.runLater(() -> {
            rebuildPending = false;
            rebuild();
        });
    }

    private void rebuild() {
        String selectedKey = getSelectionModel().getSelectedItem() == null
                ? null : identity(getSelectionModel().getSelectedItem().getValue());
        Set<String> seenKeys = new HashSet<>();
        Set<String> expandedKeys = new HashSet<>();
        Integer firstRow = null;
        boolean first = getRoot() == null;
        if (!first) {
            collectState(getRoot(), seenKeys, expandedKeys);
            firstRow = firstVisibleRow();
        }

        TreeItem<Node> root = new TreeItem<>(new Node(Kind.ROOT, "root", null, null, null, null));
        TreeItem<Node> local = group(Kind.BRANCHES, "Branches", "mdi2s-source-branch");
        TreeItem<Node> remotes = group(Kind.REMOTES, "Remotes", "mdi2c-cloud-outline");
        TreeItem<Node> tags = group(Kind.TAGS, "Tags", "mdi2t-tag-outline");
        TreeItem<Node> stashes = group(Kind.STASHES, "Stashes", "mdi2a-archive-outline");
        // In a GitButler workspace, local branches are split into applied (in a stack) and unapplied ones.
        boolean butMode = vm.isButWorkspace();
        TreeItem<Node> unappliedGroup = group(Kind.GROUP, "Unapplied", "mdi2s-source-branch-minus");
        Map<String, TreeItem<Node>> branchToStackGroup = new HashMap<>();
        List<TreeItem<Node>> stackGroups = new ArrayList<>();
        if (butMode) {
            ButStatus status = vm.gitButler().statusProperty().get();
            if (status != null) {
                int stackNumber = 0;
                for (ButStatus.Stack stack : status.stacks()) {
                    stackNumber++;
                    String color = CommitCell.stackColor(stackNumber);
                    TreeItem<Node> g = group(Kind.GROUP, "S" + stackNumber, "mdi2s-source-branch", color);
                    g.setExpanded(true);
                    stackGroups.add(g);
                    for (ButStatus.Branch branch : stack.branches()) {
                        String name = branch.name();
                        if (name != null && !name.isBlank()) {
                            branchToStackGroup.put(name, g);
                        }
                    }
                }
            }
        }
        Map<String, TreeItem<Node>> perRemote = new LinkedHashMap<>();
        for (String r : vm.remoteNames()) {
            TreeItem<Node> g = remoteGroup(r);
            perRemote.put(r, g);
            remotes.getChildren().add(g);
        }

        vm.refs().stream().sorted(Comparator.comparing(GitRef::shortName, String.CASE_INSENSITIVE_ORDER)).forEach(r -> {
            switch (r.type()) {
                case LOCAL_BRANCH -> {
                    String branchLabel = r.shortName();
                    if (r.head()) {
                        var status = vm.statusProperty().get();
                        if (status != null) {
                            int ahead = status.ahead();
                            int behind = status.behind();
                            if (ahead > 0 || behind > 0) {
                                branchLabel += "  ↑" + ahead + " ↓" + behind;
                            }
                        }
                    }
                    if (!butMode) {
                        local.getChildren().add(leaf(r, branchLabel, "mdi2s-source-branch"));
                    } else {
                        TreeItem<Node> stackGroup = branchToStackGroup.get(r.shortName());
                        if (stackGroup != null) {
                            stackGroup.getChildren().add(leaf(r, branchLabel, "mdi2s-source-branch",
                                    stackGroup.getValue().color()));
                        } else {
                            unappliedGroup.getChildren().add(leaf(r, branchLabel, "mdi2s-source-branch"));
                        }
                    }
                }
                case TAG -> tags.getChildren().add(leaf(r, r.shortName(), "mdi2t-tag-outline"));
                case REMOTE_BRANCH -> {
                    String remote = SidebarMenus.remoteOf(vm, r);
                    String branch = GitRef.branchOf(r.shortName(), remote);
                    perRemote.computeIfAbsent(remote, n -> {
                        TreeItem<Node> g = remoteGroup(n);
                        remotes.getChildren().add(g);
                        return g;
                    }).getChildren().add(leaf(r, branch, "mdi2s-source-branch"));
                }
            }
        });
        if (butMode) {
            for (TreeItem<Node> g : stackGroups) {
                if (!g.getChildren().isEmpty()) local.getChildren().add(g);
            }
            java.util.Set<String> known = new HashSet<>();
            unappliedGroup.getChildren().forEach(i -> known.add(i.getValue().label()));
            for (var b : vm.gitButler().unappliedBranchesProperty().get()) {
                String name = b.name();
                if (name == null || name.isBlank()) continue;
                if (branchToStackGroup.containsKey(name)) continue;
                if (known.contains(name)) continue;
                unappliedGroup.getChildren().add(new TreeItem<>(new Node(Kind.BUT_BRANCH, name, null,
                        "mdi2s-source-branch", null, null, b, null)));
            }
            if (!unappliedGroup.getChildren().isEmpty()) local.getChildren().add(unappliedGroup);
        }
        for (StashEntry s : vm.stashes()) {
            stashes.getChildren().add(new TreeItem<>(new Node(Kind.STASH, s.ref() + "  " + s.message(), null,
                    "mdi2a-archive-outline", s, null)));
        }
        root.getChildren().addAll(local, remotes, tags, stashes);
        if (!vm.submodules().isEmpty()) {
            TreeItem<Node> subs = group(Kind.SUBMODULES, "Submodules", "mdi2p-package-variant-closed");
            Map<String, TreeItem<Node>> byPath = new LinkedHashMap<>();
            for (SubmoduleInfo s : vm.submodules()) {
                String mark = s.state() == SubmoduleInfo.State.CLEAN && !s.dirty() ? "" : "  (" + s.statusText() + ")";
                TreeItem<Node> item = new TreeItem<>(new Node(Kind.SUBMODULE, s.path() + mark, null,
                        "mdi2p-package-variant-closed", null, null, s));
                String key = "submodule|" + s.path();
                item.setExpanded(first || !seenKeys.contains(key) || expandedKeys.contains(key));
                byPath.put(s.path(), item);
                TreeItem<Node> parent = parentSubmodule(byPath, s.path());
                (parent == null ? subs.getChildren() : parent.getChildren()).add(item);
            }
            root.getChildren().add(subs);
        }
        TreeItem<Node> trees = group(Kind.WORKTREES, "Worktrees", "mdi2f-folder-multiple-outline");
        for (WorktreeInfo w : vm.worktrees()) {
            String cur = SidebarMenus.isCurrent(vm, w) ? "  (current)" : "";
            trees.getChildren().add(new TreeItem<>(new Node(Kind.WORKTREE, w.label() + cur, null,
                    "mdi2f-folder-outline", null, null, w)));
        }
        root.getChildren().add(trees);

        if (section != null) {
            TreeItem<Node> only = root.getChildren().stream().filter(g -> g.getValue().kind() == section)
                    .findFirst().orElse(null);
            if (only != null) root.getChildren().setAll(only.getChildren());
            else root.getChildren().clear();
        }
        setRoot(root);
        for (TreeItem<Node> g : root.getChildren()) {
            g.setExpanded(first ? !g.getValue().label().equals("Tags") : expandedKeys.contains(identity(g.getValue())));
            for (TreeItem<Node> sub : g.getChildren()) {
                if (!sub.isLeaf()) {
                    String key = identity(sub.getValue());
                    // New groups start expanded; known ones keep what the user chose.
                    sub.setExpanded(first || !seenKeys.contains(key) || expandedKeys.contains(key));
                }
            }
        }

        restoring = true;
        try {
            TreeItem<Node> item = selectedKey == null ? null : findByIdentity(root, selectedKey);
            if (item == null) getSelectionModel().clearSelection();
            else getSelectionModel().select(item);
        } finally {
            restoring = false;
        }
        if (firstRow != null) {
            int row = firstRow;
            javafx.application.Platform.runLater(() -> scrollTo(row));
        }
    }

    /** Closest ancestor submodule of a nested path ("a/b/c" is under "a/b"), or null at the top level. */
    private static TreeItem<Node> parentSubmodule(Map<String, TreeItem<Node>> byPath, String path) {
        String p = path;
        while (p.lastIndexOf('/') > 0) {
            p = p.substring(0, p.lastIndexOf('/'));
            TreeItem<Node> item = byPath.get(p);
            if (item != null) return item;
        }
        return null;
    }

    /** Stable identity of a node across rebuilds: ref type + full name, stash hash, submodule/worktree path. */
    private static String identity(Node n) {
        if (n == null) return null;
        return switch (n.kind()) {
            case REF -> "ref|" + n.ref().fullName();
            case STASH -> "stash|" + n.stash().hash();
            case SUBMODULE -> "submodule|" + ((SubmoduleInfo) n.data()).path();
            case WORKTREE -> "worktree|" + ((WorktreeInfo) n.data()).path();
            default -> n.kind() + "|" + n.label();
        };
    }

    private static void collectState(TreeItem<Node> item, Set<String> seen, Set<String> expanded) {
        for (TreeItem<Node> child : item.getChildren()) {
            String key = identity(child.getValue());
            seen.add(key);
            if (child.isExpanded()) expanded.add(key);
            collectState(child, seen, expanded);
        }
    }

    private static TreeItem<Node> findByIdentity(TreeItem<Node> item, String key) {
        for (TreeItem<Node> child : item.getChildren()) {
            if (key.equals(identity(child.getValue()))) return child;
            TreeItem<Node> found = findByIdentity(child, key);
            if (found != null) return found;
        }
        return null;
    }

    /** Index of the first visible row, or null when the tree has not been laid out yet. */
    private Integer firstVisibleRow() {
        javafx.scene.Node flow = lookup(".virtual-flow");
        if (flow instanceof javafx.scene.control.skin.VirtualFlow<?> vf) {
            var cell = vf.getFirstVisibleCell();
            if (cell != null && cell.getIndex() >= 0) return cell.getIndex();
        }
        return null;
    }

    // ------------------------------------------------------------ cells

    private final class RefCell extends TreeCell<Node> {
        RefCell() {
            setOnContextMenuRequested(e -> {
                Node n = getItem();
                if (n == null || isEmpty()) return;
                ContextMenu m = menus.forNode(n);
                if (m != null) {
                    m.show(getScene().getWindow(), e.getScreenX(), e.getScreenY());
                    e.consume();
                }
            });
        }

        @Override
        protected void updateItem(Node n, boolean empty) {
            super.updateItem(n, empty);
            if (empty || n == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            setText(n.label());
            FontIcon icon = new FontIcon(n.icon());
            if (n.color() != null) {
                icon.setIconColor(javafx.scene.paint.Color.web(n.color()));
            }
            setGraphic(icon);
            String url = n.kind() == Kind.REMOTE ? vm.remoteUrls().get(n.remote()) : null;
            setTooltip(url == null || url.isBlank() ? null : new Tooltip(RemoteUrls.display(url)));
            boolean bold = (n.ref() != null && n.ref().head()) || n.ref() == null && n.stash() == null && n.data() == null;
            setFont(Font.font(getFont().getFamily(), bold ? FontWeight.BOLD : FontWeight.NORMAL, getFont().getSize()));
        }
    }
}
