package com.github.gitguiorg.gitgui.ui.viewmodel;

import com.github.gitguiorg.gitgui.model.ReflogEntry;
import com.github.gitguiorg.gitgui.model.RepoStatus;
import com.github.gitguiorg.gitgui.ui.Fx;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

/** Reflog of HEAD or of one branch, newest first. FX-thread only. */
public final class ReflogViewModel {
    private static final int LIMIT = 200;

    private final RepoViewModel repo;
    private final ObservableList<ReflogEntry> entries = FXCollections.observableArrayList();
    private final StringProperty ref = new SimpleStringProperty("HEAD");
    private String loadedHead;

    public ReflogViewModel(RepoViewModel repo) {
        this.repo = repo;
        ref.addListener((o, a, b) -> load());
        repo.statusProperty().addListener((o, a, b) -> {
            String h = b == null ? null : b.headOid();
            if (h != null && !h.equals(loadedHead)) load();
        });
    }

    public ObservableList<ReflogEntry> entries() { return entries; }

    /** Ref whose reflog is shown: {@code HEAD} or a branch name. */
    public StringProperty refProperty() { return ref; }

    public void load() {
        RepoStatus s = repo.statusProperty().get();
        if (s == null || !com.github.gitguiorg.gitgui.util.Hashes.isObjectId(s.headOid())) {
            entries.clear();
            return;
        }
        String which = ref.get();
        repo.session().async(() -> repo.session().git().reflog(which, LIMIT)).whenComplete((list, err) -> Fx.run(() -> {
            if (!which.equals(ref.get())) return;
            if (err != null) { repo.reportError(err); return; }
            RepoStatus now = repo.statusProperty().get();
            loadedHead = now == null ? null : now.headOid();
            entries.setAll(list);
        }));
    }

    /** Shows the commit this entry points at in the shared diff pane. */
    public void select(ReflogEntry e) {
        if (e == null) repo.diff().clear();
        else repo.diff().showCommitByHash(e.hash(), e.summary());
    }
}
