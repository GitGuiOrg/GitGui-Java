package com.github.gitguiorg.gitgui.ui.view;

import com.github.gitguiorg.gitgui.ui.viewmodel.RepoViewModel;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.Tooltip;
import org.kordamp.ikonli.javafx.FontIcon;

/** Sidebar with one tab per section (branches, remotes, tags, ...); only the selected tab shows its name. */
public final class SidebarTabs extends TabPane {
    public SidebarTabs(RepoViewModel vm) {
        setTabClosingPolicy(TabClosingPolicy.UNAVAILABLE);
        add(vm, "Branches", "mdi2s-source-branch", SidebarView.Kind.BRANCHES);
        add(vm, "Remotes", "mdi2c-cloud-outline", SidebarView.Kind.REMOTES);
        add(vm, "Tags", "mdi2t-tag-outline", SidebarView.Kind.TAGS);
        add(vm, "Stashes", "mdi2a-archive-outline", SidebarView.Kind.STASHES);
        add(vm, "Submodules", "mdi2p-package-variant-closed", SidebarView.Kind.SUBMODULES);
        add(vm, "Worktrees", "mdi2f-folder-multiple-outline", SidebarView.Kind.WORKTREES);
        getSelectionModel().selectedItemProperty().addListener((o, a, b) -> label());
        label();
    }

    private void add(RepoViewModel vm, String name, String icon, SidebarView.Kind kind) {
        Tab t = new Tab(null, new SidebarView(vm, kind));
        t.setClosable(false);
        t.setUserData(name);
        t.setGraphic(new FontIcon(icon));
        t.setTooltip(new Tooltip(name));
        getTabs().add(t);
    }

    private void label() {
        for (Tab t : getTabs()) t.setText(t == getSelectionModel().getSelectedItem() ? (String) t.getUserData() : null);
    }
}
