package com.github.gitguiorg.gitgui.ui.viewmodel;

import com.github.gitguiorg.gitgui.model.BlameLine;
import com.github.gitguiorg.gitgui.ui.Fx;
import java.util.List;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;

/** Blame of one file at a revision (null = working tree). FX-thread only. */
public final class BlameViewModel {
    private final RepoViewModel repo;
    private final String path;
    private final String rev;
    private final ObjectProperty<List<BlameLine>> lines = new SimpleObjectProperty<>();

    public BlameViewModel(RepoViewModel repo, String path, String rev) {
        this.repo = repo;
        this.path = path;
        this.rev = rev;
    }

    public String path() { return path; }
    public String rev() { return rev; }
    public ObjectProperty<List<BlameLine>> linesProperty() { return lines; }

    public void load() {
        repo.session().async(() -> repo.session().git().blame(path, rev)).whenComplete((l, err) -> Fx.run(() -> {
            if (err != null) repo.reportError(err); else lines.set(l);
        }));
    }
}
