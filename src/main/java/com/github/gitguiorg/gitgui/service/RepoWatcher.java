package com.github.gitguiorg.gitgui.service;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Watches a working tree and the relevant parts of {@code .git} (HEAD, index, refs) and fires a debounced
 * callback. The callback runs on a background thread, never on the JavaFX thread.
 */
public final class RepoWatcher implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(RepoWatcher.class);
    /** Heavy, git-ignored-by-convention directories that are not watched. */
    private static final Set<String> SKIP_DIRS = Set.of("node_modules", "target", "build", ".idea", ".gradle");

    private final Path root;
    private final Path gitDir;
    /** Shared git directory: in a linked worktree it differs from {@link #gitDir} (refs and objects live there). */
    private final Path commonDir;
    private final Runnable onChange;
    private final long debounceMs;
    private final WatchService watch;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("repo-debounce").factory());
    private final Thread pump;
    private ScheduledFuture<?> pending;
    private volatile boolean closed;

    public RepoWatcher(Path root, Path gitDir, Path commonDir, long debounceMs, Runnable onChange) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        this.gitDir = gitDir.toAbsolutePath().normalize();
        this.commonDir = commonDir.toAbsolutePath().normalize();
        this.debounceMs = debounceMs;
        this.onChange = onChange;
        this.watch = this.root.getFileSystem().newWatchService();
        registerTree(this.root);
        registerGitDir(this.gitDir);
        if (!this.commonDir.equals(this.gitDir)) registerGitDir(this.commonDir);
        this.pump = Thread.ofVirtual().name("repo-watch").start(this::loop);
    }

    /** Registers the files that change on git operations, plus the recursive refs tree. */
    private void registerGitDir(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return;
        register(dir);
        Path refs = dir.resolve("refs");
        if (Files.isDirectory(refs)) registerTree(refs);
    }

    private void register(Path dir) {
        try {
            dir.register(watch, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_DELETE,
                    StandardWatchEventKinds.ENTRY_MODIFY);
        } catch (IOException e) {
            LOG.debug("Cannot watch {}: {}", dir, e.getMessage());
        }
    }

    private void registerTree(Path start) throws IOException {
        Files.walkFileTree(start, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.equals(gitDir) && !start.equals(gitDir)) return FileVisitResult.SKIP_SUBTREE;
                Path name = dir.getFileName();
                if (name != null && !dir.equals(start) && SKIP_DIRS.contains(name.toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                register(dir);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void loop() {
        while (!closed) {
            WatchKey key;
            try {
                key = watch.take();
            } catch (InterruptedException | ClosedWatchServiceException e) {
                return;
            }
            Path dir = (Path) key.watchable();
            boolean relevant = false;
            for (WatchEvent<?> ev : key.pollEvents()) {
                if (ev.kind() == StandardWatchEventKinds.OVERFLOW) { relevant = true; continue; }
                Path child = dir.resolve((Path) ev.context());
                if (isRelevant(child)) {
                    relevant = true;
                    if (ev.kind() == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(child)
                            && !child.equals(gitDir) && !child.equals(commonDir) && !insideObjects(child)) {
                        try { registerTree(child); } catch (IOException ignored) { /* vanished */ }
                    }
                }
            }
            key.reset();
            if (relevant) schedule();
        }
    }

    /** Inside git directories only HEAD, index, refs and merge/rebase state files matter; locks and objects are noise. */
    private boolean isRelevant(Path p) {
        if (p.startsWith(gitDir)) {
            String rel = gitDir.relativize(p).toString().replace('\\', '/');
            if (rel.endsWith(".lock")) return false;
            return rel.equals("HEAD") || rel.equals("index") || rel.equals("ORIG_HEAD") || rel.equals("MERGE_HEAD")
                    || rel.equals("FETCH_HEAD") || rel.equals("packed-refs") || rel.startsWith("refs/")
                    || rel.startsWith("rebase-") || rel.equals("CHERRY_PICK_HEAD") || rel.equals("REVERT_HEAD")
                    || rel.equals("BISECT_LOG") || rel.startsWith("worktrees/");
        }
        if (!commonDir.equals(gitDir) && p.startsWith(commonDir)) {
            String rel = commonDir.relativize(p).toString().replace('\\', '/');
            return rel.equals("packed-refs") || rel.startsWith("refs/") || rel.startsWith("worktrees/");
        }
        return true;
    }

    private boolean insideObjects(Path p) {
        return p.startsWith(gitDir.resolve("objects")) || p.startsWith(commonDir.resolve("objects"));
    }

    private synchronized void schedule() {
        if (closed) return;
        if (pending != null) pending.cancel(false);
        pending = scheduler.schedule(() -> {
            try {
                onChange.run();
            } catch (RuntimeException e) {
                LOG.warn("Repo change handler failed", e);
            }
        }, debounceMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        closed = true;
        scheduler.shutdownNow();
        pump.interrupt();
        try {
            watch.close();
        } catch (IOException ignored) {
            // closing
        }
    }
}
