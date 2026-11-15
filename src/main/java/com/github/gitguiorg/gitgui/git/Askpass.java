package com.github.gitguiorg.gitgui.git;

import com.github.gitguiorg.gitgui.util.AppDirs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * GIT_ASKPASS / SSH_ASKPASS bridge. git runs a small shell script that drops the prompt into a per-command temp
 * directory and waits for the answer; a poller in this process asks the {@link Prompter} and writes the reply.
 * Nothing is stored here: saving credentials is up to git's credential helper.
 */
final class Askpass implements AutoCloseable {
    private static final String SCRIPT = """
            #!/bin/sh
            d="$GITGUI_ASKPASS_DIR"
            [ -n "$d" ] || exit 1
            id="$$"
            printf '%s' "$1" > "$d/req.$id.tmp" || exit 1
            mv "$d/req.$id.tmp" "$d/req.$id" || exit 1
            n=0
            while [ $n -lt 6000 ]; do
              if [ -f "$d/resp.$id" ]; then cat "$d/resp.$id"; exit 0; fi
              if [ -f "$d/cancel.$id" ]; then exit 1; fi
              sleep 0.1
              n=$((n+1))
            done
            exit 1
            """;

    private static Path scriptPath;

    private final Path dir;
    private final Prompter prompter;
    private final java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile boolean closed;
    private Thread poller;

    private Askpass(Path dir, Prompter prompter) {
        this.dir = dir;
        this.prompter = prompter;
    }

    /** Script that git executes; created on first use. */
    private static synchronized Path script() throws IOException {
        if (scriptPath == null) {
            Path p = AppDirs.configDir().resolve("askpass.sh");
            Path parent = p.getParent();
            if (parent != null) Files.createDirectories(parent);
            byte[] want = SCRIPT.getBytes(StandardCharsets.UTF_8);
            if (!Files.isRegularFile(p) || !java.util.Arrays.equals(Files.readAllBytes(p), want)) Files.write(p, want);
            makeExecutable(p);
            scriptPath = p;
        }
        return scriptPath;
    }

    /** git runs the script directly, so on POSIX it needs the executable bit; Windows ignores it. */
    private static void makeExecutable(Path p) {
        try {
            if (Files.getFileStore(p).supportsFileAttributeView(java.nio.file.attribute.PosixFileAttributeView.class)) {
                Files.setPosixFilePermissions(p,
                        java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
            }
        } catch (IOException | UnsupportedOperationException e) {
            // best effort: git reports the failure if it cannot run the script
        }
    }

    /** Prepares the environment of a git process; returns null when the bridge cannot be set up. */
    static Askpass prepare(Prompter prompter, java.util.Map<String, String> env) {
        try {
            Path script = script();
            Path dir = Files.createTempDirectory("gitgui-askpass");
            String s = script.toString();
            env.put("GIT_ASKPASS", s);
            env.put("SSH_ASKPASS", s);
            env.put("SSH_ASKPASS_REQUIRE", "force");
            env.put("GITGUI_ASKPASS_DIR", dir.toString().replace(java.io.File.separatorChar, '/'));
            return new Askpass(dir, prompter);
        } catch (IOException e) {
            return null;
        }
    }

    /** Starts answering prompts until {@code p} exits or this object is closed. */
    void start(Process p) {
        poller = Thread.startVirtualThread(() -> {
            while (!closed && p.isAlive()) {
                try {
                    handleRequests();
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    return;
                } catch (IOException | RuntimeException ignored) {
                    // keep polling; a broken prompt must not kill the git command
                }
            }
        });
    }

    private void handleRequests() throws IOException {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "req.*")) {
            for (Path req : ds) {
                Path fileName = req.getFileName();
                if (fileName == null) continue;
                String name = fileName.toString();
                if (name.endsWith(".tmp")) continue;
                String id = name.substring("req.".length());
                String prompt;
                try {
                    prompt = Files.readString(req, StandardCharsets.UTF_8);
                    Files.deleteIfExists(req);
                } catch (IOException e) {
                    continue;
                }
                Optional<String> answer;
                try {
                    answer = prompter.ask(prompt, Prompter.isSecret(prompt));
                } catch (RuntimeException e) {
                    answer = Optional.empty();
                }
                if (answer.isPresent()) {
                    Path tmp = dir.resolve("resp." + id + ".tmp");
                    Files.writeString(tmp, answer.get(), StandardCharsets.UTF_8);
                    Files.move(tmp, dir.resolve("resp." + id), StandardCopyOption.ATOMIC_MOVE);
                } else {
                    cancelled.set(true);
                    Files.writeString(dir.resolve("cancel." + id), "");
                }
            }
        }
    }

    /** True when at least one prompt was dismissed, which normally makes git abort. */
    boolean wasCancelled() {
        return cancelled.get();
    }

    @Override
    public void close() {
        closed = true;
        if (poller != null) poller.interrupt();
        try (var s = Files.walk(dir)) {
            s.sorted(java.util.Comparator.reverseOrder()).forEach(f -> {
                try {
                    Files.deleteIfExists(f);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        } catch (IOException ignored) {
            // best effort
        }
    }
}
