package com.github.gitguiorg.gitgui.ui.viewmodel;

import com.github.gitguiorg.gitgui.model.Commit;
import com.github.gitguiorg.gitgui.model.FileDiff;
import com.github.gitguiorg.gitgui.model.StashEntry;
import com.github.gitguiorg.gitgui.model.StatusEntry;
import com.github.gitguiorg.gitgui.service.DiffPreferences;
import com.github.gitguiorg.gitgui.service.RepoSession;
import com.github.gitguiorg.gitgui.git.GitClient.DiffOptions;
import com.github.gitguiorg.gitgui.ui.Fx;
import com.github.gitguiorg.gitgui.util.Hashes;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;

/** What the diff pane currently shows: a commit, a stash, or one working-tree/staged file. FX-thread only. */
public final class DiffViewModel {
    public enum Mode { NONE, COMMIT, RANGE, UNSTAGED, UNTRACKED, STAGED }

    /** @param rev commit hash when showing a commit, otherwise null */
    public record Content(Mode mode, String title, List<FileDiff> files, String rev, boolean ignoreWs,
                          boolean ignoreCrAtEol, boolean ignoreBlankLines) {
        public static final Content EMPTY = new Content(Mode.NONE, "", List.of(), null, false, false, false);

        public boolean ignoresDifferences() { return ignoreWs || ignoreCrAtEol || ignoreBlankLines; }

        public boolean editable() {
            return !ignoresDifferences() && (mode == Mode.UNSTAGED || mode == Mode.UNTRACKED || mode == Mode.STAGED)
                    && files.size() == 1 && !files.get(0).binary();
        }
    }

    private final RepoSession session;
    private final DiffPreferences prefs;
    private final Consumer<Throwable> errorHandler;
    private final ObjectProperty<Content> content = new SimpleObjectProperty<>(Content.EMPTY);
    private final AtomicLong seq = new AtomicLong();
    private Runnable lastLoad;

    public DiffViewModel(RepoSession session, DiffPreferences prefs, Consumer<Throwable> errorHandler) {
        this.session = session;
        this.prefs = prefs;
        this.errorHandler = errorHandler;
        prefs.ignoreWhitespaceProperty().addListener((o, a, b) -> reload());
        prefs.ignoreCrAtEolProperty().addListener((o, a, b) -> reload());
        prefs.ignoreBlankLinesProperty().addListener((o, a, b) -> reload());
        prefs.contextLinesProperty().addListener((o, a, b) -> reload());
    }

    public ObjectProperty<Content> contentProperty() { return content; }

    /** Ignore whitespace changes; staging is disabled while on because such a diff cannot be applied as a patch. */
    public BooleanProperty ignoreWhitespaceProperty() { return prefs.ignoreWhitespaceProperty(); }
    public BooleanProperty ignoreCrAtEolProperty() { return prefs.ignoreCrAtEolProperty(); }
    public BooleanProperty ignoreBlankLinesProperty() { return prefs.ignoreBlankLinesProperty(); }
    public BooleanProperty sideBySideProperty() { return prefs.sideBySideProperty(); }
    public BooleanProperty syntaxProperty() { return prefs.syntaxProperty(); }
    public IntegerProperty contextLinesProperty() { return prefs.contextLinesProperty(); }
    public IntegerProperty maxLinesProperty() { return prefs.maxLinesProperty(); }
    /** True when an option hides part of the differences: the shown diff cannot be applied as a patch. */
    public boolean ignoresDifferences() { return prefs.ignoresDifferences(); }

    private DiffOptions options() {
        return new DiffOptions(prefs.contextLinesProperty().get(), prefs.ignoreWhitespaceProperty().get(),
                prefs.ignoreCrAtEolProperty().get(), prefs.ignoreBlankLinesProperty().get());
    }

    /** Re-runs the last load, e.g. after an option changed. */
    public void reload() {
        if (lastLoad != null) lastLoad.run();
    }

    public void clear() {
        lastLoad = null;
        seq.incrementAndGet();
        content.set(Content.EMPTY);
    }

    public void showCommit(Commit c) {
        if (c == null) { clear(); return; }
        showCommitByHash(c.hash(), c.subject());
    }

    public void showCommitByHash(String hash, String subject) {
        String shortHash = Hashes.shortHash(hash);
        load(Mode.COMMIT, shortHash + "  " + subject, hash, () -> session.git().diffCommit(hash, options()));
    }

    /** Shows an already loaded comparison, without reloading it when the pane is resized. */
    public void showRange(String title, String baseRev, List<FileDiff> files, boolean ignoreWs) {
        lastLoad = null;
        seq.incrementAndGet();
        content.set(new Content(Mode.RANGE, title, files, baseRev, ignoreWs, false, false));
    }

    /** The diff of a single file in a commit (falls back to the whole commit, e.g. after a rename). */
    public void showCommitFile(Commit c, String path) {
        load(Mode.COMMIT, c.shortHash() + "  " + c.subject(), c.hash(), () -> {
            List<FileDiff> one = session.git().diffCommitFile(c.hash(), path, options());
            return one.isEmpty() ? session.git().diffCommit(c.hash(), options()) : one;
        });
    }

    public void showStash(StashEntry s) {
        if (s == null) { clear(); return; }
        load(Mode.COMMIT, s.ref() + "  " + s.message(), null, () -> session.git().stashDiff(s.ref(), options()));
    }

    public void showFile(StatusEntry e, boolean staged) {
        if (e == null) { clear(); return; }
        if (staged) {
            load(Mode.STAGED, e.path(), null, () -> session.git().diffStaged(e.path(), options()));
        } else if (e.kind() == StatusEntry.Kind.UNTRACKED || e.kind() == StatusEntry.Kind.IGNORED) {
            load(Mode.UNTRACKED, e.path(), null, () -> session.git().diffUntracked(e.path(), options()));
        } else {
            load(Mode.UNSTAGED, e.path(), null, () -> session.git().diffWorkingTree(e.path(), options()));
        }
    }

    private void load(Mode mode, String title, String rev, java.util.concurrent.Callable<List<FileDiff>> loader) {
        lastLoad = () -> load(mode, title, rev, loader);
        boolean ws = prefs.ignoreWhitespaceProperty().get();
        boolean cr = prefs.ignoreCrAtEolProperty().get();
        boolean blank = prefs.ignoreBlankLinesProperty().get();
        long mine = seq.incrementAndGet();
        session.async(loader).whenComplete((files, err) -> Fx.run(() -> {
            if (mine != seq.get()) return;
            if (err != null) { errorHandler.accept(err); return; }
            content.set(new Content(mode, title, files, rev, ws, cr, blank));
        }));
    }
}
