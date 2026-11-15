package com.github.gitguiorg.gitgui.service;

import com.github.gitguiorg.gitgui.git.CancelToken;
import com.github.gitguiorg.gitgui.git.GitClient;
import com.github.gitguiorg.gitgui.git.GitRunner;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One open repository (one UI tab): a {@link GitClient}, a file watcher and a virtual-thread executor.
 * Listeners are invoked on background threads; the UI must marshal to the FX thread itself.
 */
public final class RepoSession implements AutoCloseable {
    private static final ExecutorService EXEC = Executors.newVirtualThreadPerTaskExecutor();

    private final GitClient git;
    private final RepoWatcher watcher;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final List<CancelToken> running = new CopyOnWriteArrayList<>();

    private RepoSession(GitClient git, long debounceMs) throws IOException {
        this.git = git;
        // A linked worktree keeps .git as a file, so the real git directories must be passed in.
        this.watcher = new RepoWatcher(git.root(), git.gitDirPath(), git.commonDirPath(), debounceMs,
                this::fireChanged);
    }

    public static RepoSession open(Path dir, GitRunner runner) throws IOException {
        return new RepoSession(GitClient.open(dir, runner), 300);
    }

    public GitClient git() { return git; }

    public Path root() { return git.root(); }

    public String name() {
        Path n = git.root().getFileName();
        return n == null ? git.root().toString() : n.toString();
    }

    /** Registers a listener fired (debounced) when the repo changes on disk or after {@link #notifyChanged()}. */
    public void onChanged(Runnable r) { listeners.add(r); }

    /** Forces a refresh notification, e.g. after an operation the watcher might miss. */
    public void notifyChanged() { fireChanged(); }

    private void fireChanged() {
        for (Runnable r : listeners) r.run();
    }

    /** Runs a blocking git operation on a virtual thread. */
    public <T> CompletableFuture<T> async(Callable<T> task) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return task.call();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }, EXEC);
    }

    /** Same as {@link #async(Callable)} for operations with no result; notifies listeners when done. */
    public CompletableFuture<Void> mutate(Runnable task) {
        return CompletableFuture.runAsync(task, EXEC).whenComplete((v, e) -> fireChanged());
    }

    public CancelToken newCancelToken() {
        CancelToken t = new CancelToken();
        running.add(t);
        return t;
    }

    @Override
    public void close() {
        watcher.close();
        running.forEach(CancelToken::cancel);
        listeners.clear();
    }
}