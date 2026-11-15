package com.github.gitguiorg.gitgui.ui.viewmodel;

import com.github.gitguiorg.gitgui.git.CancelToken;
import com.github.gitguiorg.gitgui.git.GitCancelledException;
import com.github.gitguiorg.gitgui.git.GitClient;
import com.github.gitguiorg.gitgui.git.GitException;
import com.github.gitguiorg.gitgui.model.GitProgress;
import com.github.gitguiorg.gitgui.model.GitRef;
import com.github.gitguiorg.gitgui.model.RepoStatus;
import com.github.gitguiorg.gitgui.service.RepoSession;
import com.github.gitguiorg.gitgui.service.Settings;
import com.github.gitguiorg.gitgui.ui.Fx;
import com.github.gitguiorg.gitgui.util.Texts;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

/** Fetch / pull / push with progress, cancellation and background auto-fetch. FX-thread only. */
public final class NetworkViewModel {
    /** Dialogs the view must provide. */
    public interface Ui {
        Optional<String> chooseRemote(List<String> remotes);

        /** @return true if the user wants to pull after a rejected push */
        boolean offerPull(String gitMessage);
    }

    @FunctionalInterface
    private interface NetOp {
        void run(CancelToken token) throws Exception;
    }

    private final RepoViewModel repo;
    private final RepoSession session;
    private final Settings settings;
    private final BooleanProperty busy = new SimpleBooleanProperty();
    private final StringProperty phase = new SimpleStringProperty("");
    private final DoubleProperty progress = new SimpleDoubleProperty(-1);
    private final StringProperty warning = new SimpleStringProperty("");

    private Ui ui = new Ui() {
        @Override public Optional<String> chooseRemote(List<String> remotes) { return Optional.empty(); }
        @Override public boolean offerPull(String gitMessage) { return false; }
    };
    private volatile CancelToken current;
    private boolean autoPaused;
    private ScheduledExecutorService scheduler;
    private volatile String lastProgress = "";

    public NetworkViewModel(RepoViewModel repo, Settings settings) {
        this.repo = repo;
        this.session = repo.session();
        this.settings = settings;
    }

    public BooleanProperty busyProperty() { return busy; }
    public StringProperty phaseProperty() { return phase; }
    public DoubleProperty progressProperty() { return progress; }
    public StringProperty warningProperty() { return warning; }
    public void setUi(Ui ui) { this.ui = ui; }

    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "auto-fetch");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(() -> Fx.run(this::tick), 1, 1, TimeUnit.MINUTES);
    }

    private int minutesSinceFetch;

    /** Runs every minute; the interval is read from the settings each time, so changes apply immediately. */
    private void tick() {
        int every = settings.autoFetchMinutes;
        if (every <= 0) { minutesSinceFetch = 0; return; }
        if (++minutesSinceFetch >= every) {
            minutesSinceFetch = 0;
            fetch(true);
        }
    }

    public void stop() {
        if (scheduler != null) scheduler.shutdownNow();
        cancel();
    }

    public void cancel() {
        CancelToken t = current;
        if (t != null) t.cancel();
    }

    // ------------------------------------------------------------ operations

    public void fetch(boolean auto) {
        if (busy.get() || (auto && autoPaused)) return;
        run("Fetching…", auto, t -> session.git().fetch(null, true, !auto, progressCallback(), t), () -> {
            autoPaused = false;
            warning.set("");
        });
    }

    public void pull() {
        if (busy.get()) return;
        GitClient.PullMode mode;
        try {
            mode = GitClient.PullMode.valueOf(settings.pullMode);
        } catch (IllegalArgumentException e) {
            mode = GitClient.PullMode.FF_ONLY;
        }
        GitClient.PullMode m = mode;
        run("Pulling…", false, t -> session.git().pull(m, progressCallback(), t), null);
    }

    public void push() {
        if (busy.get()) return;
        RepoStatus s = repo.statusProperty().get();
        if (s == null || s.isDetached()) {
            repo.reportError(new IllegalStateException("Cannot push: no branch is checked out."));
            return;
        }
        if (s.upstream() != null) {
            // The upstream branch may have another name: push to it explicitly, as a bare "git push" would
            // be refused by push.default=simple instead of updating it.
            doPush(GitRef.remoteOf(s.upstream(), repo.remoteNames()),
                    GitRef.pushRefspec(s.branch(), s.upstream(), repo.remoteNames()), false);
            return;
        }
        busy.set(true);
        phase.set("Looking up remotes…");
        progress.set(-1);
        session.async(() -> session.git().remotes()).whenComplete((remotes, err) -> Fx.run(() -> {
            busy.set(false);
            phase.set("");
            if (err != null) { repo.reportError(err); return; }
            if (remotes.isEmpty()) {
                repo.reportError(new IllegalStateException("No remote is configured for this repository."));
            } else if (remotes.size() == 1) {
                doPush(remotes.get(0), s.branch(), true);
            } else {
                ui.chooseRemote(remotes).ifPresent(r -> doPush(r, s.branch(), true));
            }
        }));
    }

    private void doPush(String remote, String branch, boolean setUpstream) {
        run("Pushing…", false, t -> session.git().push(remote, branch, setUpstream, false, progressCallback(), t), null);
    }

    /** A git network operation run through {@link #execute}. */
    @FunctionalInterface
    public interface Task {
        void run(GitClient git, java.util.function.Consumer<GitProgress> progress, CancelToken token) throws Exception;
    }

    /** Runs an arbitrary network operation with the shared progress / cancel / error handling. */
    public void execute(String label, Task task) {
        if (busy.get()) return;
        run(label, false, t -> task.run(session.git(), progressCallback(), t), null);
    }

    // ------------------------------------------------------------ plumbing

    private java.util.function.Consumer<GitProgress> progressCallback() {
        return p -> {
            String text = p.phase() + " " + p.percent() + "%";
            if (text.equals(lastProgress)) return;
            lastProgress = text;
            Fx.run(() -> {
                phase.set(text);
                progress.set(p.percent() / 100.0);
            });
        };
    }

    private void run(String label, boolean auto, NetOp op, Runnable onSuccess) {
        busy.set(true);
        phase.set(label);
        progress.set(-1);
        lastProgress = "";
        CancelToken token = new CancelToken();
        current = token;
        session.async(() -> { op.run(token); return null; }).whenComplete((v, err) -> Fx.run(() -> {
            busy.set(false);
            phase.set("");
            progress.set(-1);
            current = null;
            session.notifyChanged();
            if (err == null) {
                if (onSuccess != null) onSuccess.run();
                return;
            }
            Throwable e = Fx.unwrap(err);
            if (e instanceof GitCancelledException) return;
            if (e instanceof com.github.gitguiorg.gitgui.git.GitAuthCancelledException) {
                warning.set("Authentication cancelled: the operation was not completed.");
                return;
            }
            if (auto) {
                autoPaused = true;
                warning.set("Auto-fetch paused: " + firstLine(e.getMessage()) + " (press Fetch to retry)");
                return;
            }
            handleError(e);
        }));
    }

    private void handleError(Throwable e) {
        if (e instanceof GitException g && isRejectedPush(g.stderr())) {
            if (ui.offerPull(g.stderr().strip())) pull();
            return;
        }
        repo.reportError(e);
    }

    static boolean isRejectedPush(String stderr) {
        return stderr != null && stderr.contains("rejected")
                && (stderr.contains("non-fast-forward") || stderr.contains("fetch first"));
    }

    private static String firstLine(String s) {
        String line = Texts.firstLine(s, 120);
        return line.isEmpty() ? "unknown error" : line;
    }
}
