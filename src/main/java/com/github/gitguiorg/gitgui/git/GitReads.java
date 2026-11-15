package com.github.gitguiorg.gitgui.git;

import com.github.gitguiorg.gitgui.model.Commit;
import com.github.gitguiorg.gitgui.model.FileDiff;
import com.github.gitguiorg.gitgui.model.GitRef;
import com.github.gitguiorg.gitgui.model.RepoStatus;
import java.util.ArrayList;
import java.util.List;

/** Read-only status, history, diff and ref queries of a {@link GitClient}. */
final class GitReads {
    private final GitClient git;

    GitReads(GitClient git) {
        this.git = git;
    }

    RepoStatus status(boolean includeIgnored) {
        GitCommand c = git.cmd("status", "--porcelain=v2", "--branch", "-z", "--untracked-files=all");
        if (includeIgnored) c.args("--ignored");   // with -uall git lists every ignored file, not just the folders
        return StatusParser.parse(git.read(c).out());
    }

    boolean hasCommits() {
        return git.readRaw(git.cmd("rev-parse", "--verify", "-q", "HEAD")).exitCode() == 0;
    }

    /** Commit history in topological order; {@code allRefs} includes every branch and tag. */
    List<Commit> log(int skip, int limit, boolean allRefs) {
        if (!hasCommits()) return List.of();
        List<String> a = GitHistory.logArgs(skip, limit);
        if (allRefs) a.add("--all");
        return LogParser.parse(git.read(git.cmd().args(a)).out());
    }

    /**
     * Diff between two revisions: {@code git diff a b}, {@code git diff a...b} with {@code mergeBase},
     * or {@code git diff a} against the working tree when {@code b} is null.
     *
     * @param path only this file or folder, or null for the whole range
     */
    List<FileDiff> diffRange(String a, String b, String path, int context, boolean ignoreWs, boolean mergeBase) {
        List<String> args = new ArrayList<>(List.of("diff", "--no-color", "--no-ext-diff", "-M", "-U" + context));
        if (ignoreWs) args.add("-w");
        if (b == null) args.add(a);
        else args.add(a + (mergeBase ? "..." : "..") + b);
        if (path != null) { args.add("--"); args.add(path); }
        return DiffParser.parse(git.read(git.cmd().args(args)).out());
    }

    /** Commits reachable from {@code b} but not from {@code a} ({@code git log a..b}); {@code b} null means HEAD. */
    List<Commit> logRange(String a, String b) {
        String to = b == null ? "HEAD" : b;
        return LogParser.parse(git.read(git.cmd("log", "--topo-order", "--format=" + LogParser.FORMAT, a + ".." + to)).out());
    }

    List<FileDiff> diffWorkingTree(String path, GitClient.DiffOptions o) {
        List<String> a = new ArrayList<>(List.of("diff", "--no-color", "--no-ext-diff"));
        a.addAll(o.flags());
        a.add("--");
        a.add(path);
        return DiffParser.parse(git.read(git.cmd().args(a)).stdout());
    }

    List<FileDiff> diffStaged(String path, GitClient.DiffOptions o) {
        List<String> a = new ArrayList<>(List.of("diff", "--cached", "--no-color", "--no-ext-diff"));
        a.addAll(o.flags());
        a.add("--");
        a.add(path);
        return DiffParser.parse(git.read(git.cmd().args(a)).stdout());
    }

    List<FileDiff> diffUntracked(String path, GitClient.DiffOptions o) {
        List<String> a = new ArrayList<>(List.of("diff", "--no-index", "--no-color", "--no-ext-diff"));
        a.addAll(o.flags());
        a.add("--");
        a.add("/dev/null");
        a.add(path);
        return DiffParser.parse(git.read(git.cmd().args(a).allowExit(1)).stdout());
    }

    List<FileDiff> diffCommit(String hash, GitClient.DiffOptions o) {
        List<String> a = new ArrayList<>(List.of("show", "--format=", "--patch", "-M", "--first-parent", "--no-color",
                "--no-ext-diff"));
        a.addAll(o.flags());
        a.add(hash);
        return DiffParser.parse(git.read(git.cmd().args(a)).stdout());
    }

    List<GitRef> refs() {
        String fmt = "%(refname)%00%(refname:short)%00%(objectname)%00%(*objectname)%00%(upstream:short)%00%(HEAD)";
        String out = git.read(git.cmd("for-each-ref", "--format=" + fmt, "refs/heads", "refs/remotes", "refs/tags")).out();
        List<GitRef> refs = new ArrayList<>();
        for (String line : out.split("\n")) {
            if (line.isBlank()) continue;
            String[] f = line.split("\0", -1);
            if (f.length < 6) continue;
            GitRef.Type type;
            if (f[0].startsWith("refs/heads/")) type = GitRef.Type.LOCAL_BRANCH;
            else if (f[0].startsWith("refs/remotes/")) type = GitRef.Type.REMOTE_BRANCH;
            else type = GitRef.Type.TAG;
            if (type == GitRef.Type.REMOTE_BRANCH && f[0].endsWith("/HEAD")) continue;
            String hash = f[3].isBlank() ? f[2] : f[3];
            refs.add(new GitRef(type, f[0], f[1], hash, f[4].isBlank() ? null : f[4], f[5].strip().equals("*")));
        }
        return refs;
    }

    List<String> remotes() {
        String out = git.read(git.cmd("remote")).out();
        return out.isBlank() ? List.of() : List.of(out.strip().split("\\R"));
    }
}
