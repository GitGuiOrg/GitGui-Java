package com.github.gitguiorg.gitgui.ui.viewmodel;

import com.github.gitguiorg.gitgui.model.Commit;
import com.github.gitguiorg.gitgui.model.GraphLayout;
import com.github.gitguiorg.gitgui.model.GraphRow;
import com.github.gitguiorg.gitgui.model.LogFilter;
import com.github.gitguiorg.gitgui.service.RepoSession;
import com.github.gitguiorg.gitgui.ui.Fx;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.ObservableSet;

/** Paged, optionally filtered commit history with graph layout. Mutated on the FX thread only. */
public final class LogViewModel {
    public record CommitRow(Commit commit, GraphRow graph) {}

    private record Loaded(List<Commit> commits, List<GraphRow> graph, GraphLayout layout) {}

    /** Row without edges, used for text-filtered results where parents are generally not in the list. */
    private static final GraphRow FLAT = new GraphRow(0, 0, 1, List.of(), List.of(), List.of());

    private final RepoSession session;
    private final Consumer<Throwable> errorHandler;
    private final IntSupplier pageSize;
    private final ObservableList<CommitRow> rows = FXCollections.observableArrayList();
    private final IntegerProperty graphLanes = new SimpleIntegerProperty(1);
    private final ObjectProperty<Commit> selected = new SimpleObjectProperty<>();
    private final IntegerProperty highlightedStack = new SimpleIntegerProperty();
    private final ObjectProperty<LogFilter> filter = new SimpleObjectProperty<>(LogFilter.NONE);
    private final StringProperty bisectBad = new SimpleStringProperty("");
    private final ObservableSet<String> bisectGood = FXCollections.observableSet();
    private final ObservableSet<String> starred = FXCollections.observableSet();
    private final BooleanProperty starredOnly = new SimpleBooleanProperty();

    private GraphLayout layout = new GraphLayout();
    private boolean loading;
    private boolean exhausted;
    private long generation;

    public LogViewModel(RepoSession session, IntSupplier pageSize, Consumer<Throwable> errorHandler) {
        this.session = session;
        this.pageSize = pageSize;
        this.errorHandler = errorHandler;
        selected.addListener((o, a, c) -> highlightedStack.set(c == null ? 0 : stackOf(c.hash())));
    }

    public ObservableList<CommitRow> rows() { return rows; }
    public IntegerProperty graphLanesProperty() { return graphLanes; }
    private java.util.function.ToIntFunction<String> stackOf = h -> 0;
    private final IntegerProperty stackTick = new SimpleIntegerProperty();
    /** Stack number (1-based, 0 = none) of a commit hash, for GitButler workspaces. */
    public void setStackOf(java.util.function.ToIntFunction<String> f) { this.stackOf = f; }
    public int stackOf(String hash) { return stackOf.applyAsInt(hash); }
    public IntegerProperty stackTickProperty() { return stackTick; }
    public ObjectProperty<Commit> selectedProperty() { return selected; }
    /** Stack number (1-based, 0 = none) of the currently selected commit, for graph row highlighting. */
    public IntegerProperty highlightedStackProperty() { return highlightedStack; }
    public int highlightedStack() { return highlightedStack.get(); }
    public ObjectProperty<LogFilter> filterProperty() { return filter; }

    /** Hash of the commit bisect marked as bad, empty when no bisect is running. */
    public StringProperty bisectBadProperty() { return bisectBad; }

    /** Hashes of the commits bisect marked as good. */
    public ObservableSet<String> bisectGoodProperty() { return bisectGood; }

    public void setBisect(String bad, List<String> good) {
        bisectBad.set(bad == null ? "" : bad);
        if (!bisectGood.equals(java.util.Set.copyOf(good))) {
            bisectGood.retainAll(good);
            bisectGood.addAll(good);
        }
    }

    public boolean isBisectBad(String hash) { return hash.equals(bisectBad.get()); }
    public boolean isBisectGood(String hash) { return bisectGood.contains(hash); }

    /** Hashes the user starred in this clone. */
    public ObservableSet<String> starredProperty() { return starred; }

    public BooleanProperty starredOnlyProperty() { return starredOnly; }

    public boolean isStarred(String hash) { return starred.contains(hash); }

    public void setStarred(List<String> hashes) {
        if (starred.equals(java.util.Set.copyOf(hashes))) return;
        starred.retainAll(hashes);
        starred.addAll(hashes);
        if (starredOnly.get()) reload();   // the visible rows are exactly this list
    }

    /** Shows only the starred commits (no graph, paged like the normal history). */
    public void setStarredOnly(boolean on) {
        if (starredOnly.get() == on) return;
        starredOnly.set(on);
        reload(true);
    }

    /** Applies a new filter and reloads from the top. */
    public void setFilter(LogFilter f) {
        LogFilter next = f == null ? LogFilter.NONE : f;
        if (next.equals(filter.get())) return;
        filter.set(next);
        reload(true);
    }

    public void reload() { reload(false); }

    /** @param reset load a single page instead of keeping at least as many commits as are currently loaded */
    private void reload(boolean reset) {
        int page = pageSize.getAsInt();
        int want = reset ? page : Math.max(page, rows.size());
        LogFilter f = filter.get();
        long gen = ++generation;
        loading = true;
        session.async(() -> starredOnly.get() ? fetchStarred(want) : fetch(f, 0, want, new GraphLayout()))
                .whenComplete((res, err) -> Fx.run(() -> {
                    if (gen != generation) return;
                    loading = false;
                    if (err != null) { errorHandler.accept(err); return; }
                    layout = res.layout();
                    exhausted = res.commits().size() < want;
                    rows.setAll(toRows(res));
                    updateLanes();
                    Commit sel = selected.get();
                    if (sel != null && rows.stream().noneMatch(r -> r.commit().hash().equals(sel.hash()))) selected.set(null);
                }));
    }

    /** Loads the next page (no-op while loading or when the history is exhausted). */
    public void loadMore() {
        if (loading || exhausted) return;
        long gen = generation;
        int page = pageSize.getAsInt();
        if (starredOnly.get()) {
            int want = rows.size() + page;
            loading = true;
            session.async(() -> fetchStarred(want)).whenComplete((res, err) -> Fx.run(() -> {
                if (gen != generation) return;
                loading = false;
                if (err != null) { errorHandler.accept(err); return; }
                exhausted = res.commits().size() < want;
                rows.setAll(toRows(res));
                updateLanes();
            }));
            return;
        }
        loading = true;
        int skip = rows.size();
        LogFilter f = filter.get();
        GraphLayout l = layout;
        session.async(() -> fetch(f, skip, page, l))
                .whenComplete((res, err) -> Fx.run(() -> {
                    if (gen != generation) return;
                    loading = false;
                    if (err != null) { errorHandler.accept(err); return; }
                    exhausted = res.commits().size() < page;
                    rows.addAll(toRows(res));
                    updateLanes();
                }));
    }

    /** The starred commits, newest first, limited to {@code count}; empty and flat because a star list has no graph. */
    private Loaded fetchStarred(int count) {
        List<String> hashes = session.git().starredCommits();
        if (hashes.isEmpty()) return new Loaded(List.of(), List.of(), new GraphLayout());
        List<Commit> limited = session.git().logByHash(hashes).stream().limit(count).toList();
        return new Loaded(limited, limited.stream().map(c -> FLAT).toList(), new GraphLayout());
    }

    private Loaded fetch(LogFilter f, int skip, int count, GraphLayout l) {
        List<Commit> cs = f.isActive() ? session.git().searchLog(f, skip, count) : session.git().log(skip, count, true);
        List<GraphRow> graph = f.flat() ? cs.stream().map(c -> FLAT).toList() : l.append(cs);
        return new Loaded(cs, graph, l);
    }

    private static List<CommitRow> toRows(Loaded l) {
        return java.util.stream.IntStream.range(0, l.commits().size())
                .mapToObj(i -> new CommitRow(l.commits().get(i), l.graph().get(i))).toList();
    }

    private void updateLanes() {
        int max = 1;
        for (CommitRow r : rows) max = Math.max(max, r.graph().laneCount());
        graphLanes.set(Math.min(max, 12));
    }
}
