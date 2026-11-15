package com.github.gitguiorg.gitgui.git;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Executes the git CLI. Thread-safe and stateless. */
public final class GitRunner {
    private static final List<String> BASE_ARGS = List.of(
            "--no-pager", "-c", "core.quotepath=off", "-c", "i18n.logOutputEncoding=UTF-8");

    /** One completed git invocation, kept for the command console. */
    public record CommandRecord(java.util.List<String> command, java.nio.file.Path dir, int exitCode,
                                long durationMs, String stderr, java.time.Instant at) {
        public String commandLine() { return String.join(" ", command); }
    }

    private static final int HISTORY_LIMIT = 500;
    private final java.util.concurrent.ConcurrentLinkedDeque<CommandRecord> history =
            new java.util.concurrent.ConcurrentLinkedDeque<>();
    private final java.util.concurrent.CopyOnWriteArrayList<java.util.function.Consumer<CommandRecord>> historyListeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    private final String executable;
    private volatile Prompter prompter;

    /** @param override explicit path to git, or null/blank to search the PATH */
    public GitRunner(String override) {
        this.executable = locate(override);
    }

    /** Prompter used for commands marked {@link GitCommand#interactive()}; null disables prompting. */
    public GitRunner setPrompter(Prompter p) { this.prompter = p; return this; }

    /** Completed commands, oldest first. */
    public java.util.List<CommandRecord> history() { return java.util.List.copyOf(history); }

    public void clearHistory() { history.clear(); }

    /** Notified on the calling thread after each completed command. */
    public void addHistoryListener(java.util.function.Consumer<CommandRecord> listener) {
        historyListeners.add(listener);
    }

    private void record(CommandRecord r) {
        history.addLast(r);
        while (history.size() > HISTORY_LIMIT) history.pollFirst();
        for (java.util.function.Consumer<CommandRecord> l : historyListeners) {
            try {
                l.accept(r);
            } catch (RuntimeException ignored) {
                // a broken listener must not affect git
            }
        }
    }

    public static String locate(String override) {
        return locate(override, System.getenv("PATH"));
    }

    /** @param pathEnv value of PATH; a parameter so the search can be tested without touching the environment */
    static String locate(String override, String pathEnv) {
        String found = ExecutableFinder.find(override, pathEnv, List.of("git.exe", "git"), List.of(), List.of());
        if (found != null) return found;
        if (override != null && !override.isBlank()) {
            throw new GitException("git executable not found: " + override, List.of(), -1, "");
        }
        throw new GitException("git executable not found in PATH", List.of(), -1, "");
    }

    /** Runs the command; throws {@link GitException} on unexpected exit codes. */
    public GitResult run(GitCommand cmd) {
        GitResult r = runRaw(cmd);
        if (!cmd.okExit.contains(r.exitCode())) throw r.toException();
        return r;
    }

    /** Runs the command and returns the result whatever the exit code (timeouts/cancel still throw). */
    public GitResult runRaw(GitCommand cmd) {
        List<String> full = new ArrayList<>();
        full.add(executable);
        full.addAll(BASE_ARGS);
        full.addAll(cmd.args);

        ProcessBuilder pb = new ProcessBuilder(full).directory(cmd.dir.toFile());
        var env = pb.environment();
        env.put("GIT_TERMINAL_PROMPT", "0");
        env.put("GIT_EDITOR", "true");
        env.put("LC_ALL", "C");
        if (cmd.readOnly) env.put("GIT_OPTIONAL_LOCKS", "0");
        env.putAll(cmd.env);
        Askpass askpass = cmd.interactive && prompter != null ? Askpass.prepare(prompter, env) : null;

        long started = System.nanoTime();
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            if (askpass != null) askpass.close();
            throw new GitException("cannot start git: " + e.getMessage(), e);
        }
        cmd.cancel.attach(p);
        if (askpass != null) askpass.start(p);

        AtomicBoolean timedOut = new AtomicBoolean();
        Thread watchdog = Thread.startVirtualThread(() -> {
            try {
                if (!p.waitFor(cmd.timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    timedOut.set(true);
                    p.destroyForcibly();
                }
            } catch (InterruptedException ignored) {
                // process finished
            }
        });

        StringBuilder err = new StringBuilder();
        Thread errReader = Thread.startVirtualThread(() -> drainStderr(p, err, cmd));
        Thread.startVirtualThread(() -> {
            try (OutputStream os = p.getOutputStream()) {
                if (cmd.stdin != null) os.write(cmd.stdin);
            } catch (IOException ignored) {
                // process may exit before reading stdin
            }
        });

        byte[] out;
        int exit;
        try {
            out = p.getInputStream().readAllBytes();
            exit = p.waitFor();
            errReader.join();
        } catch (IOException e) {
            p.destroyForcibly();
            // A cancel kills the process, which closes the stream: that is a cancellation, not an I/O failure.
            if (cmd.cancel.isCancelled()) throw new GitCancelledException(full, "");
            throw new GitException("I/O error reading git output: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new GitCancelledException(full, "");
        } finally {
            watchdog.interrupt();
            if (askpass != null) askpass.close();
        }

        String stderr;
        synchronized (err) { stderr = err.toString(); }
        if (cmd.cancel.isCancelled()) throw new GitCancelledException(full, stderr);
        if (timedOut.get()) throw new GitTimeoutException(full, stderr);
        if (askpass != null && askpass.wasCancelled() && exit != 0) throw new GitAuthCancelledException(full, stderr);
        record(new CommandRecord(java.util.List.copyOf(full), cmd.dir, exit,
                (System.nanoTime() - started) / 1_000_000, stderr, java.time.Instant.now()));
        return new GitResult(full, exit, out, stderr);
    }

    /** Reads stderr, splitting progress updates on both CR and LF, and forwards each piece. */
    private static void drainStderr(Process p, StringBuilder sink, GitCommand cmd) {
        StringBuilder line = new StringBuilder();
        try (Reader r = new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8)) {
            int c;
            while ((c = r.read()) != -1) {
                synchronized (sink) { sink.append((char) c); }
                if (c == '\r' || c == '\n') {
                    flush(line, cmd);
                } else {
                    line.append((char) c);
                }
            }
            flush(line, cmd);
        } catch (IOException ignored) {
            // stream closed on kill
        }
    }

    private static void flush(StringBuilder line, GitCommand cmd) {
        if (line.length() > 0 && cmd.stderrLines != null) cmd.stderrLines.accept(line.toString());
        line.setLength(0);
    }
}
