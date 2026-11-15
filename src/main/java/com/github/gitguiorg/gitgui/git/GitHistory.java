package com.github.gitguiorg.gitgui.git;

import com.github.gitguiorg.gitgui.model.BlameLine;
import com.github.gitguiorg.gitgui.model.Commit;
import com.github.gitguiorg.gitgui.model.FileDiff;
import com.github.gitguiorg.gitgui.model.LogFilter;
import java.util.ArrayList;
import java.util.List;

/** History, search, blame and per-file diffs of a {@link GitClient}. */
final class GitHistory {
    private final GitClient git;

    GitHistory(GitClient git) {
        this.git = git;
    }

    static List<String> logArgs(int skip, int limit) {
        List<String> a = new ArrayList<>(List.of("log", "--topo-order", "--format=" + LogParser.FORMAT));
        if (skip > 0) a.add("--skip=" + skip);
        if (limit > 0) a.add("-n" + limit);
        return a;
    }

    /** History of a single file, following renames. */
    List<Commit> fileHistory(String path, int skip, int limit) {
        if (!git.hasCommits()) return List.of();
        List<String> a = logArgs(skip, limit);
        a.add("--follow");
        a.add("--");
        a.add(path);
        return LogParser.parse(git.read(git.cmd().args(a)).out());
    }

    /**
     * History filtered by message, author, file, hash or changed content, optionally limited to one branch
     * ({@code "HEAD"} = current). Text is matched literally and case-insensitively; the content mode finds the
     * commits that added or removed the given string ({@code git log -S}).
     */
    List<Commit> search(LogFilter f, int skip, int limit) {
        if (!git.hasCommits()) return List.of();
        String text = f.text() == null ? "" : f.text().strip();
        if (f.mode() == LogFilter.Mode.HASH && !text.isEmpty()) {
            GitResult r = git.readRaw(git.cmd("rev-parse", "--verify", "-q", text + "^{commit}"));
            if (r.exitCode() != 0) return List.of();
            String hash = r.out().strip();
            // The branch filter is a restriction also here: a hash outside that history must not be shown.
            if (f.branch() != null && git.readRaw(git.cmd("merge-base", "--is-ancestor", hash, f.branch())).exitCode() != 0) {
                return List.of();
            }
            return LogParser.parse(git.read(git.cmd("log", "-n1", "--format=" + LogParser.FORMAT, hash)).out());
        }
        List<String> a = logArgs(skip, limit);
        a.add(f.branch() == null ? "--all" : f.branch());
        if (!text.isEmpty()) {
            switch (f.mode()) {
                case MESSAGE -> { a.add("--grep=" + text); a.add("-i"); a.add("-F"); }
                case AUTHOR -> { a.add("--author=" + text); a.add("-i"); a.add("-F"); }
                case FILE -> { a.add("--"); a.add(text); }
                case CONTENT -> { a.add("-S" + text); a.add("-i"); }
                case HASH -> { }
            }
        }
        return LogParser.parse(git.read(git.cmd().args(a)).out());
    }

    List<BlameLine> blame(String path, String rev) {
        List<String> a = new ArrayList<>(List.of("blame", "--porcelain"));
        if (rev != null) a.add(rev);
        a.add("--");
        a.add(path);
        return BlameParser.parse(git.read(git.cmd().args(a)).out());
    }

    /** Diff of a single file in a commit (against its first parent). */
    List<FileDiff> diffCommitFile(String hash, String path, GitClient.DiffOptions o) {
        List<String> a = new ArrayList<>(List.of("show", "--format=", "--patch", "-M", "--first-parent", "--no-color",
                "--no-ext-diff"));
        a.addAll(o.flags());
        a.add(hash);
        a.add("--");
        a.add(path);
        return DiffParser.parse(git.read(git.cmd().args(a)).stdout());
    }
}
