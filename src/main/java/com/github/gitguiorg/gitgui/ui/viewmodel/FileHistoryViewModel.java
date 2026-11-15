package com.github.gitguiorg.gitgui.ui.viewmodel;

import com.github.gitguiorg.gitgui.model.Commit;
import com.github.gitguiorg.gitgui.ui.Fx;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

/** Paged history of one file (follows renames). FX-thread only. */
public final class FileHistoryViewModel {
    private static final int PAGE = 100;

    private final RepoViewModel repo;
    private final String path;
    private final ObservableList<Commit> commits = FXCollections.observableArrayList();
    private boolean loading;
    private boolean exhausted;

    public FileHistoryViewModel(RepoViewModel repo, String path) {
        this.repo = repo;
        this.path = path;
    }

    public String path() { return path; }
    public ObservableList<Commit> commits() { return commits; }

    public void loadMore() {
        if (loading || exhausted) return;
        loading = true;
        int skip = commits.size();
        repo.session().async(() -> repo.session().git().fileHistory(path, skip, PAGE)).whenComplete((cs, err) -> Fx.run(() -> {
            loading = false;
            if (err != null) { repo.reportError(err); return; }
            exhausted = cs.size() < PAGE;
            commits.addAll(cs);
        }));
    }

    public void select(Commit c) {
        if (c == null) repo.diff().clear(); else repo.diff().showCommitFile(c, path);
    }
}
