package com.github.gitguiorg.gitgui.git;

import com.github.gitguiorg.gitgui.model.GitProgress;
import com.github.gitguiorg.gitgui.util.RemoteUrls;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.function.Consumer;

/** Repository cloning of a {@link GitClient}. */
final class GitClone {
    private GitClone() {
    }

    /** Folder name git would pick for a URL: "https://h/o/r.git" -> "r", "git@h:o/r.git" -> "r". */
    static String repoNameFromUrl(String url) {
        return RemoteUrls.repoName(url);
    }

    /**
     * Clones {@code url} into {@code parent/name}. On failure or cancellation a directory created by this call is
     * removed. Authentication is left to git (credential manager / ssh-agent): terminal prompts are disabled.
     */
    static Path cloneRepository(GitRunner runner, String url, Path parent, String name,
                                Consumer<GitProgress> progress, CancelToken cancel) {
        Path target = parent.resolve(name);
        boolean existed = Files.exists(target);
        try {
            Files.createDirectories(parent);
        } catch (java.io.IOException e) {
            throw new GitException("cannot create " + parent + ": " + e.getMessage(), e);
        }
        GitCommand c = GitCommand.in(parent).args("clone", "--progress", "--", url, name)
                .timeout(Duration.ofHours(1)).interactive();
        if (cancel != null) c.cancel(cancel);
        if (progress != null) c.onStderrLine(line -> ProgressParser.parse(line).ifPresent(progress));
        try {
            runner.run(c);
        } catch (RuntimeException e) {
            if (!existed) deleteQuietly(target);
            throw e;
        }
        return target;
    }

    private static void deleteQuietly(Path dir) {
        if (!Files.exists(dir)) return;
        try (var s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    p.toFile().setWritable(true);   // git object files are read-only on Windows
                    Files.deleteIfExists(p);
                } catch (java.io.IOException ignored) {
                    // best effort
                }
            });
        } catch (java.io.IOException ignored) {
            // best effort
        }
    }
}
