package com.github.gitguiorg.gitgui.git;

import com.github.gitguiorg.gitgui.model.Commit;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Per-clone starred commits of a {@link GitClient}. */
final class GitStarred {
    /** Hashes per invocation: a long list would otherwise exceed the OS limit on the command line. */
    private static final int LOG_BY_HASH_BATCH = 100;

    private final GitClient git;

    GitStarred(GitClient git) {
        this.git = git;
    }

    private Path starredFile() {
        return git.gitDirPath().resolve("gitgui").resolve("starred");
    }

    /** Hashes of the commits the user starred in this repository, in insertion order. */
    List<String> starredCommits() {
        Path f = starredFile();
        if (!Files.isRegularFile(f)) return List.of();
        try {
            return Files.readAllLines(f).stream().map(String::strip).filter(s -> !s.isEmpty()).distinct().toList();
        } catch (java.io.IOException e) {
            return List.of();
        }
    }

    /** Adds or removes a star; the list is kept in {@code .gcom/github/gitguiorg/gitgui/starred}, so it is per clone. */
    void setStarred(String hash, boolean starred) {
        List<String> list = new ArrayList<>(starredCommits());
        if (starred) {
            if (!list.contains(hash)) list.add(hash);
        } else {
            list.remove(hash);
        }
        Path f = starredFile();
        try {
            if (list.isEmpty()) {
                Files.deleteIfExists(f);
            } else {
                Path parent = f.getParent();
                if (parent != null) Files.createDirectories(parent);
                Files.write(f, list);
            }
        } catch (java.io.IOException e) {
            throw new GitException("cannot write " + f + ": " + e.getMessage(), e);
        }
    }

    /** The given commits in commit-date order (newest first), used by the "starred only" filter. */
    List<Commit> logByHash(List<String> hashes) {
        if (hashes.isEmpty()) return List.of();
        List<Commit> all = new ArrayList<>(hashes.size());
        for (int i = 0; i < hashes.size(); i += LOG_BY_HASH_BATCH) {
            List<String> a = new ArrayList<>(List.of("log", "--no-walk", "--format=" + LogParser.FORMAT));
            a.addAll(hashes.subList(i, Math.min(i + LOG_BY_HASH_BATCH, hashes.size())));
            all.addAll(LogParser.parse(git.read(git.cmd().args(a)).out()));
        }
        // Each batch is newest first on its own: sort again so the whole list is, whatever the batch size.
        all.sort(Comparator.comparing(Commit::authorDate).reversed());
        return all;
    }
}
