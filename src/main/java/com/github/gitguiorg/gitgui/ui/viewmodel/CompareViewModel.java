package com.github.gitguiorg.gitgui.ui.viewmodel;

import com.github.gitguiorg.gitgui.model.Commit;
import com.github.gitguiorg.gitgui.model.FileDiff;
import com.github.gitguiorg.gitgui.ui.Fx;
import com.github.gitguiorg.gitgui.util.Hashes;
import java.util.List;
import java.util.Locale;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

/**
 * Comparison between two revisions: the changed files (with a name filter) and the commits that are
 * only on the right side. FX-thread only; {@link #load()} fetches in the background.
 */
public final class CompareViewModel {
    /** @param b null compares {@code a} with the working tree */
    public record Range(String a, String b) {
        public String label() {
            return shortRev(a) + (b == null ? " vs working tree" : " vs " + shortRev(b));
        }

        /** Abbreviates full object ids (SHA-1 or SHA-256), leaves branch names, tags and expressions alone. */
        private static String shortRev(String r) {
            if (r == null) return "";
            return r.matches("[0-9a-fA-F]{40,64}") ? Hashes.shortHash(r) : r;
        }
    }

    private record Loaded(List<FileDiff> files, List<Commit> commits) {}

    private final RepoViewModel repo;
    private final Range range;
    private final ObservableList<FileDiff> files = FXCollections.observableArrayList();
    private final ObservableList<Commit> commits = FXCollections.observableArrayList();
    private final StringProperty filter = new SimpleStringProperty("");
    private final BooleanProperty mergeBase = new SimpleBooleanProperty(false);
    private List<FileDiff> all = List.of();
    private String selectedPath;
    private long seq;

    public CompareViewModel(RepoViewModel repo, String a, String b) {
        this.repo = repo;
        this.range = new Range(a, b);
        filter.addListener((o, was, now) -> applyFilter());
        mergeBase.addListener((o, was, now) -> load());
        repo.diff().ignoreWhitespaceProperty().addListener((o, was, now) -> load());
    }

    public String label() { return range.label(); }
    public ObservableList<FileDiff> files() { return files; }
    public ObservableList<Commit> commits() { return commits; }
    public StringProperty filterProperty() { return filter; }
    public BooleanProperty mergeBaseProperty() { return mergeBase; }
    public String selectedPath() { return selectedPath; }

    /** Reloads the range diff and the commits only on the right side. */
    public void load() {
        long mine = ++seq;
        String a = range.a(), b = range.b();
        boolean base = mergeBase.get();
        boolean ws = repo.diff().ignoreWhitespaceProperty().get();
        int context = repo.diffContextLines();
        repo.session().async(() -> new Loaded(
                        repo.session().git().diffRange(a, b, null, context, ws, base),
                        repo.session().git().logRange(a, b)))
                .whenComplete((loaded, err) -> Fx.run(() -> {
                    if (mine != seq) return;
                    if (err != null) { repo.reportError(err); return; }
                    all = loaded.files();
                    commits.setAll(loaded.commits());
                    applyFilter();
                    if (all.isEmpty()) selectedPath = null;
                    // Refresh the selected file too: the options (merge base, whitespace) may have changed.
                    FileDiff pick = visible(selectedPath);
                    if (pick == null && !files.isEmpty()) pick = files.get(0);
                    if (pick != null) select(pick);
                }));
    }

    private void applyFilter() {
        String f = filter.get() == null ? "" : filter.get().strip().toLowerCase(Locale.ROOT);
        boolean wasEmpty = files.isEmpty();
        files.setAll(f.isEmpty() ? all : all.stream()
                .filter(d -> d.path() != null && d.path().toLowerCase(Locale.ROOT).contains(f))
                .toList());
        // A filter that hides every file must empty the diff pane, not leave the previous file on screen.
        if (files.isEmpty()) {
            repo.diff().showRange(range.label(), range.a(), List.of(), repo.diff().ignoreWhitespaceProperty().get());
            return;
        }
        // Also the opposite: clearing a filter that hid everything must put a file back in the pane, and the
        // file on screen must never be one the filter has hidden.
        if (wasEmpty || visible(selectedPath) == null) {
            FileDiff pick = visible(selectedPath);
            select(pick != null ? pick : files.get(0));
        }
    }

    /** The entry with the given path among the visible (filtered) files, or null. */
    private FileDiff visible(String path) {
        if (path == null) return null;
        for (FileDiff d : files) if (path.equals(d.path())) return d;
        return null;
    }

    /** Shows one file of the comparison in the shared diff pane. */
    public void select(FileDiff d) {
        if (d == null) return;
        selectedPath = d.path();
        repo.diff().showRange(range.label() + "  " + d.path(), range.a(), List.of(d),
                repo.diff().ignoreWhitespaceProperty().get());
    }

    /** Re-displays the comparison when its tab is activated again, staying within the visible (filtered) files. */
    public void reshow() {
        FileDiff pick = visible(selectedPath);
        if (pick == null && !files.isEmpty()) pick = files.get(0);
        if (pick == null) repo.diff().showRange(range.label(), range.a(), List.of(),
                repo.diff().ignoreWhitespaceProperty().get());
        else select(pick);
    }

    /** Writes one patch per commit of the comparison into {@code dir}. */
    public void exportPatches(java.nio.file.Path dir) {
        repo.exportPatches(range.a(), range.b() == null ? "HEAD" : range.b(), mergeBase.get(), dir);
    }
}
