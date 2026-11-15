package com.github.gitguiorg.gitgui.git;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Runs a simple external CLI and captures its output. */
final class CliRunner {
    private final Path dir;
    private final String executable;
    private final String name;
    private final Duration timeout;
    private final Map<String, String> environment;

    CliRunner(Path dir, String executable, String name, Duration timeout, Map<String, String> environment) {
        this.dir = dir;
        this.executable = executable;
        this.name = name;
        this.timeout = timeout;
        this.environment = Map.copyOf(environment);
    }

    String run(String... args) {
        return run(null, args);
    }

    /** Runs the command; a cancelled token destroys the process and fails the call. */
    String run(CancelToken cancel, String... args) {
        List<String> command = command(args);
        ProcessBuilder pb = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true);
        pb.environment().putAll(environment);
        Process started = null;
        try {
            started = pb.start();
            Process p = started;
            if (cancel != null) cancel.attach(p);
            p.getOutputStream().close();
            StringBuffer out = new StringBuffer();
            Thread reader = Thread.startVirtualThread(() -> {
                try (var in = p.getInputStream()) {
                    out.append(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                } catch (IOException ignored) {
                    // the exit code still tells whether the command failed
                }
            });
            if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                throw new IllegalStateException(name + " timed out: " + String.join(" ", command));
            }
            reader.join(TimeUnit.SECONDS.toMillis(5));
            String text = out.toString();
            if (cancel != null && cancel.isCancelled()) {
                throw new IllegalStateException(name + " cancelled: " + String.join(" ", command));
            }
            if (p.exitValue() != 0) {
                throw new IllegalStateException(name + " failed (exit " + p.exitValue() + "): " + text.strip());
            }
            return text;
        } catch (IOException e) {
            throw new IllegalStateException("cannot run " + executable + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            if (started != null) started.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while running " + executable, e);
        }
    }

    /**
     * Runs the command keeping stdout and stderr separate. Returns stdout and uses stderr only in the
     * failure message, so warnings printed by the tool cannot corrupt JSON output.
     */
    String runStdout(CancelToken cancel, String... args) {
        List<String> command = command(args);
        ProcessBuilder pb = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(false);
        pb.environment().putAll(environment);
        Process started = null;
        try {
            started = pb.start();
            Process p = started;
            if (cancel != null) cancel.attach(p);
            p.getOutputStream().close();
            StringBuffer out = new StringBuffer();
            StringBuffer err = new StringBuffer();
            Thread outReader = drain(p.getInputStream(), out);
            Thread errReader = drain(p.getErrorStream(), err);
            if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                throw new IllegalStateException(name + " timed out: " + String.join(" ", command));
            }
            outReader.join(TimeUnit.SECONDS.toMillis(5));
            errReader.join(TimeUnit.SECONDS.toMillis(5));
            String stdout = out.toString();
            String stderr = err.toString();
            if (cancel != null && cancel.isCancelled()) {
                throw new IllegalStateException(name + " cancelled: " + String.join(" ", command));
            }
            if (p.exitValue() != 0) {
                String detail = stderr.isBlank() ? stdout : stderr;
                throw new IllegalStateException(name + " failed (exit " + p.exitValue() + "): " + detail.strip());
            }
            return stdout;
        } catch (IOException e) {
            throw new IllegalStateException("cannot run " + executable + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            if (started != null) started.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while running " + executable, e);
        }
    }

    private List<String> command(String... args) {
        List<String> command = new ArrayList<>(args.length + 1);
        command.add(executable);
        command.addAll(List.of(args));
        return command;
    }

    private static Thread drain(InputStream stream, StringBuffer sink) {
        return Thread.startVirtualThread(() -> {
            try (InputStream in = stream) {
                sink.append(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // the exit code still tells whether the command failed
            }
        });
    }
}
