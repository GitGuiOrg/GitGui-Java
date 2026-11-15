package com.github.gitguiorg.gitgui.git;

import com.github.gitguiorg.gitgui.model.FileDiff;
import com.github.gitguiorg.gitgui.util.GitPath;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Staging, ignore/clean and partial patch operations of a {@link GitClient}. */
final class GitIndex {
    /** Paths per invocation: thousands of pathspecs at once would exceed the OS limit on the command line. */
    private static final int CLEAN_BATCH = 200;

    private final GitClient git;

    GitIndex(GitClient git) {
        this.git = git;
    }

    private static byte[] nulSeparated(List<String> paths) {
        return (String.join("\0", paths) + "\0").getBytes(StandardCharsets.UTF_8);
    }

    void stage(List<String> paths) {
        if (paths.isEmpty()) return;
        git.write(git.cmd("add", "-A", "--pathspec-from-file=-", "--pathspec-file-nul").stdin(nulSeparated(paths)));
    }

    void unstage(List<String> paths) {
        if (paths.isEmpty()) return;
        GitCommand c = git.hasCommits()
                ? git.cmd("restore", "--staged", "--pathspec-from-file=-", "--pathspec-file-nul")
                : git.cmd("rm", "--cached", "-r", "-q", "--pathspec-from-file=-", "--pathspec-file-nul");
        git.write(c.stdin(nulSeparated(paths)));
    }

    /** Discards unstaged changes of tracked files. Destructive. */
    void discard(List<String> paths) {
        if (paths.isEmpty()) return;
        git.write(git.cmd("restore", "--pathspec-from-file=-", "--pathspec-file-nul").stdin(nulSeparated(paths)));
    }

    /** Tracked files that match an ignore rule: the ones {@code git rm --cached} would untrack. */
    Set<String> ignoredTrackedFiles() {
        String out = git.read(git.cmd("ls-files", "-c", "-i", "--exclude-standard", "-z")).out();
        Set<String> files = new LinkedHashSet<>();
        for (String p : out.split("\0")) if (!p.isEmpty()) files.add(p);
        return files;
    }

    /**
     * Appends a rule to the repository {@code .gitignore}, or to {@code .git/info/exclude} when
     * {@code localOnly} so that it stays in this clone. A rule already present is not added again.
     */
    void ignorePattern(String pattern, boolean localOnly) {
        String rule = pattern == null ? "" : pattern.strip();
        if (rule.isEmpty()) return;
        Path file = localOnly ? excludeFile() : git.root().resolve(".gitignore");
        try {
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            String current = Files.isRegularFile(file) ? Files.readString(file) : "";
            for (String line : current.lines().map(String::strip).toList()) {
                if (line.equals(rule)) return;
            }
            String sep = current.isEmpty() || current.endsWith("\n") ? "" : System.lineSeparator();
            Files.writeString(file, sep + rule + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (java.io.IOException e) {
            throw new GitException("cannot write " + file + ": " + e.getMessage(), e);
        }
    }

    /** The clone-local exclude file, honouring worktrees and split git dirs. */
    private Path excludeFile() {
        String rel = git.read(git.cmd("rev-parse", "--git-path", "info/exclude")).out().strip();
        Path p = Path.of(rel);
        return p.isAbsolute() ? p : git.root().resolve(p).normalize();
    }

    /** Stops tracking the given files, leaving them on disk and untouched ({@code git rm --cached}). */
    void stopTracking(List<String> paths) {
        if (paths.isEmpty()) return;
        git.write(git.cmd("rm", "--cached", "-r", "-q", "--pathspec-from-file=-", "--pathspec-file-nul")
                .stdin(nulSeparated(paths)));
    }

    /** Paths that {@code git clean -f -d} would delete; ignored files are listed only with {@code includeIgnored} (-x). */
    List<String> cleanPreview(boolean includeIgnored) {
        List<String> a = new ArrayList<>(List.of("clean", "-n", "-d"));
        if (includeIgnored) a.add("-x");
        List<String> paths = new ArrayList<>();
        for (String line : git.read(git.cmd().args(a)).out().split("\\R")) {
            String t = line.strip();
            if (!t.startsWith("Would remove ")) continue;
            String p = GitPath.unquote(t.substring("Would remove ".length()).strip());
            while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
            if (!p.isEmpty()) paths.add(p);
        }
        return paths;
    }

    /** Deletes the given untracked paths, ignored ones only when {@code includeIgnored}. Destructive. */
    void clean(List<String> paths, boolean includeIgnored) {
        if (paths.isEmpty()) return;
        for (int i = 0; i < paths.size(); i += CLEAN_BATCH) {
            List<String> a = new ArrayList<>(List.of("clean", "-f", "-d"));
            if (includeIgnored) a.add("-x");
            a.add("--");
            a.addAll(paths.subList(i, Math.min(i + CLEAN_BATCH, paths.size())));
            git.write(git.cmd().args(a));
        }
    }

    /** Stages one hunk (or a subset of its lines) of an unstaged diff. */
    void stageHunk(FileDiff diff, int hunkIndex, Set<Integer> lines) {
        applyPartial(diff, hunkIndex, lines, true, false);
    }

    /** Unstages one hunk (or a subset of its lines) of a staged diff. */
    void unstageHunk(FileDiff stagedDiff, int hunkIndex, Set<Integer> lines) {
        applyPartial(stagedDiff, hunkIndex, lines, true, true);
    }

    /** Discards one hunk (or a subset of its lines) from the working tree. Destructive. */
    void discardHunk(FileDiff diff, int hunkIndex, Set<Integer> lines) {
        applyPartial(diff, hunkIndex, lines, false, true);
    }

    private void applyPartial(FileDiff diff, int hunkIndex, Set<Integer> lines, boolean cached, boolean reverse) {
        Optional<String> patch = PatchBuilder.build(diff, hunkIndex, lines, reverse);
        if (patch.isEmpty()) return;
        applyPatch(patch.get(), cached, reverse);
    }

    void applyPatch(String patch, boolean cached, boolean reverse) {
        List<String> a = new ArrayList<>(List.of("apply", "--recount", "--whitespace=nowarn"));
        if (cached) a.add("--cached");
        if (reverse) a.add("--reverse");
        a.add("-");
        git.write(git.cmd().args(a).stdin(patch));
    }
}
