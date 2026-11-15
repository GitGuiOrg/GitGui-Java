package com.github.gitguiorg.gitgui.git;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/** Fluent description of a git invocation. */
public final class GitCommand {
    final Path dir;
    final List<String> args = new ArrayList<>();
    byte[] stdin;
    Duration timeout = Duration.ofSeconds(60);
    CancelToken cancel = new CancelToken();
    Consumer<String> stderrLines;
    boolean readOnly;
    boolean interactive;
    final java.util.Map<String, String> env = new java.util.HashMap<>();
    final Set<Integer> okExit = new HashSet<>(Set.of(0));

    private GitCommand(Path dir) { this.dir = dir; }

    public static GitCommand in(Path dir) { return new GitCommand(dir); }

    public GitCommand args(String... a) { args.addAll(List.of(a)); return this; }
    public GitCommand args(List<String> a) { args.addAll(a); return this; }
    public GitCommand stdin(byte[] data) { this.stdin = data; return this; }
    public GitCommand stdin(String text) { this.stdin = text.getBytes(StandardCharsets.UTF_8); return this; }
    public GitCommand timeout(Duration d) { this.timeout = d; return this; }
    public GitCommand cancel(CancelToken t) { this.cancel = t; return this; }
    public GitCommand onStderrLine(Consumer<String> c) { this.stderrLines = c; return this; }
    /** Sets GIT_OPTIONAL_LOCKS=0 so that read commands never take index.lock. */
    public GitCommand readOnly() { this.readOnly = true; return this; }
    /** Extra environment variable for the git process (overrides the runner defaults). */
    /** Lets git ask the user for credentials / passphrases through the runner's {@link Prompter}. */
    public GitCommand interactive() { this.interactive = true; return this; }
    public GitCommand env(String key, String value) { env.put(key, value); return this; }
    public GitCommand allowExit(int... codes) { for (int c : codes) okExit.add(c); return this; }
}
