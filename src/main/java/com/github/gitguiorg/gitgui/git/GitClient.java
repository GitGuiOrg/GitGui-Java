package com.github.gitguiorg.gitgui.git;

import com.github.gitguiorg.gitgui.model.*;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Synchronous facade over the git CLI for a single repository. Intended to be called from background
 * (virtual) threads. Reads run in parallel; writes are serialized to avoid {@code index.lock} clashes.
 * Use one instance per open repository.
 */
public final class GitClient {
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration WRITE_TIMEOUT = Duration.ofMinutes(2);
    private static final Pattern VERSION_PATTERN = Pattern.compile("(\\d+)\\.(\\d+)");

    public enum PullMode { MERGE, REBASE, FF_ONLY }
    public enum ResetMode { SOFT, MIXED, HARD, KEEP }

    private final Path root;
    private final GitRunner runner;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final GitStash stashOps = new GitStash(this);
    private final GitBisect bisectOps = new GitBisect(this);
    private final GitWorktrees worktreeOps = new GitWorktrees(this);
    private final GitMaintenance maintenance = new GitMaintenance(this);
    private final GitRefs refsOps = new GitRefs(this);
    private final GitSubmodules submoduleOps = new GitSubmodules(this);
    private final GitNetwork networkOps = new GitNetwork(this);
    private final GitHistory historyOps = new GitHistory(this);
    private final GitConflicts conflictOps = new GitConflicts(this);
    private final GitReads reads = new GitReads(this);
    private final GitIndex indexOps = new GitIndex(this);
    private final GitCommits commitOps = new GitCommits(this);
    private final GitObjects objects = new GitObjects(this);
    private final GitRebase rebaseOps = new GitRebase(this);
    private final GitPatches patchOps = new GitPatches(this);
    private final GitStarred starredOps = new GitStarred(this);
    private final GitReflog reflogOps = new GitReflog(this);
    private final GitOperations operationOps = new GitOperations(this, rebaseOps);
    private final Object versionLock = new Object();
    private String versionText;

    private GitClient(Path root, GitRunner runner) {
        this.root = root;
        this.runner = runner;
    }

    /** Opens an existing repository (any directory inside the working tree is accepted). */
    public static GitClient open(Path dir, GitRunner runner) {
        String top = runner.run(GitCommand.in(dir).args("rev-parse", "--show-toplevel").readOnly()).out().strip();
        if (top.isBlank()) throw new GitException("not a git repository: " + dir, List.of(), -1, "");
        return new GitClient(Path.of(top).toAbsolutePath().normalize(), runner);
    }

    /** Runs {@code git init} in the given directory (created if missing) and opens it. */
    public static GitClient init(Path dir, GitRunner runner) {
        Path target = dir.toAbsolutePath().normalize();
        try {
            Files.createDirectories(target);
        } catch (java.io.IOException e) {
            throw new GitException("cannot create " + target + ": " + e.getMessage(), e);
        }
        runner.run(GitCommand.in(target).args("init"));
        return open(target, runner);
    }

    public Path root() { return root; }

    /** Completed git commands of this runner, oldest first (for the command console). */
    public List<GitRunner.CommandRecord> commandHistory() { return runner.history(); }

    public void addCommandListener(java.util.function.Consumer<GitRunner.CommandRecord> listener) {
        runner.addHistoryListener(listener);
    }

    public void clearCommandHistory() { runner.clearHistory(); }

    /** Cached output of {@code git --version}, or null when git cannot be run. */
    public String version() {
        synchronized (versionLock) {
            if (versionText == null) {
                try {
                    String text = read(cmd("--version")).out().strip();
                    if (text.isBlank()) return null;
                    versionText = text;
                } catch (GitException e) {
                    return null;
                }
            }
            return versionText;
        }
    }

    /** @return {@code {major, minor}} parsed from a {@code git --version} line, or null when it has no version */
    static int[] parseVersion(String line) {
        if (line == null) return null;
        Matcher m = VERSION_PATTERN.matcher(line);
        return m.find() ? new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))} : null;
    }

    /** True when the git executable is at least {@code major.minor}; an unreadable version counts as recent. */
    public boolean atLeast(int major, int minor) {
        int[] v = parseVersion(version());
        return v == null || v[0] > major || (v[0] == major && v[1] >= minor);
    }

    // ---------------------------------------------------------------- helpers

    GitCommand cmd(String... args) {
        return GitCommand.in(root).args(args);
    }

    GitResult read(GitCommand c) {
        return read(c, READ_TIMEOUT);
    }

    GitResult read(GitCommand c, Duration timeout) {
        Lock l = lock.readLock();
        l.lock();
        try {
            return runner.run(c.readOnly().timeout(timeout));
        } finally {
            l.unlock();
        }
    }

    GitResult write(GitCommand c) {
        return write(c, WRITE_TIMEOUT);
    }

    GitResult write(GitCommand c, Duration timeout) {
        Lock l = lock.writeLock();
        l.lock();
        try {
            return runner.run(c.timeout(timeout));
        } finally {
            l.unlock();
        }
    }

    GitResult writeRaw(GitCommand c) {
        Lock l = lock.writeLock();
        l.lock();
        try {
            return runner.runRaw(c.timeout(WRITE_TIMEOUT));
        } finally {
            l.unlock();
        }
    }

    /** Runs a network command without taking a repository lock: fetch and push must not block reads. */
    GitResult runUnlocked(GitCommand c, Duration timeout) {
        return runner.run(c.timeout(timeout));
    }

    /** Internal network runner shared by ref and submodule operations. */
    GitNetwork network() { return networkOps; }

    // ---------------------------------------------------------------- reads

    public RepoStatus status() {
        return status(false);
    }

    /** Status, optionally listing every ignored file instead of only the ignored directories. */
    public RepoStatus status(boolean includeIgnored) {
        return reads.status(includeIgnored);
    }

    public boolean hasCommits() {
        return reads.hasCommits();
    }

    /** Commit history in topological order; {@code allRefs} includes every branch and tag. */
    public List<Commit> log(int skip, int limit, boolean allRefs) {
        return reads.log(skip, limit, allRefs);
    }

    /** History of a single file, following renames. */
    public List<Commit> fileHistory(String path, int skip, int limit) {
        return historyOps.fileHistory(path, skip, limit);
    }

    /**
     * Diff between two revisions: {@code git diff a b}, {@code git diff a...b} with {@code mergeBase},
     * or {@code git diff a} against the working tree when {@code b} is null.
     *
     * @param path only this file or folder, or null for the whole range
     */
    public List<FileDiff> diffRange(String a, String b, String path, int context, boolean ignoreWs, boolean mergeBase) {
        return reads.diffRange(a, b, path, context, ignoreWs, mergeBase);
    }

    /** Commits reachable from {@code b} but not from {@code a} ({@code git log a..b}); {@code b} null means HEAD. */
    public List<Commit> logRange(String a, String b) {
        return reads.logRange(a, b);
    }

    /** Options that hide part of the differences; such a diff cannot be applied as a patch. */
    public record DiffOptions(int context, boolean ignoreWs, boolean ignoreCrAtEol, boolean ignoreBlankLines) {
        public List<String> flags() {
            List<String> a = new ArrayList<>();
            if (ignoreWs) a.add("-w");
            if (ignoreCrAtEol) a.add("--ignore-cr-at-eol");
            if (ignoreBlankLines) a.add("--ignore-blank-lines");
            a.add("-U" + context);
            return a;
        }
    }

    public List<FileDiff> diffWorkingTree(String path, int context) {
        return diffWorkingTree(path, context, false);
    }

    /** ignoreWs: ignore whitespace differences (not suitable for building patches). */
    public List<FileDiff> diffWorkingTree(String path, int context, boolean ignoreWs) {
        return diffWorkingTree(path, new DiffOptions(context, ignoreWs, false, false));
    }

    public List<FileDiff> diffWorkingTree(String path, DiffOptions o) {
        return reads.diffWorkingTree(path, o);
    }

    public List<FileDiff> diffStaged(String path, int context) {
        return diffStaged(path, context, false);
    }

    /** ignoreWs: ignore whitespace differences (not suitable for building patches). */
    public List<FileDiff> diffStaged(String path, int context, boolean ignoreWs) {
        return diffStaged(path, new DiffOptions(context, ignoreWs, false, false));
    }

    public List<FileDiff> diffStaged(String path, DiffOptions o) {
        return reads.diffStaged(path, o);
    }

    /** Diff of an untracked file against /dev/null (shows it as fully added). */
    public List<FileDiff> diffUntracked(String path, int context) {
        return diffUntracked(path, context, false);
    }

    /** ignoreWs: ignore whitespace differences (not suitable for building patches). */
    public List<FileDiff> diffUntracked(String path, int context, boolean ignoreWs) {
        return diffUntracked(path, new DiffOptions(context, ignoreWs, false, false));
    }

    public List<FileDiff> diffUntracked(String path, DiffOptions o) {
        return reads.diffUntracked(path, o);
    }

    /** Diff introduced by a commit (against its first parent). */
    public List<FileDiff> diffCommit(String hash, int context) {
        return diffCommit(hash, context, false);
    }

    /** ignoreWs: ignore whitespace differences (not suitable for building patches). */
    public List<FileDiff> diffCommit(String hash, int context, boolean ignoreWs) {
        return diffCommit(hash, new DiffOptions(context, ignoreWs, false, false));
    }

    public List<FileDiff> diffCommit(String hash, DiffOptions o) {
        return reads.diffCommit(hash, o);
    }

    public List<GitRef> refs() {
        return reads.refs();
    }

    public List<String> remotes() {
        return reads.remotes();
    }

    // ---------------------------------------------------------------- staging & commit

    public void stage(List<String> paths) { indexOps.stage(paths); }

    public void unstage(List<String> paths) { indexOps.unstage(paths); }

    /** Discards unstaged changes of tracked files. Destructive. */
    public void discard(List<String> paths) { indexOps.discard(paths); }

    // ---------------------------------------------------------------- ignore & clean

    /** Tracked files that match an ignore rule: the ones {@code git rm --cached} would untrack. */
    public Set<String> ignoredTrackedFiles() { return indexOps.ignoredTrackedFiles(); }

    /**
     * Appends a rule to the repository {@code .gitignore}, or to {@code .git/info/exclude} when
     * {@code localOnly} so that it stays in this clone. A rule already present is not added again.
     */
    public void ignorePattern(String pattern, boolean localOnly) { indexOps.ignorePattern(pattern, localOnly); }

    /** Stops tracking the given files, leaving them on disk and untouched ({@code git rm --cached}). */
    public void stopTracking(List<String> paths) { indexOps.stopTracking(paths); }

    /** Paths that {@code git clean -f -d} would delete; ignored files are listed only with {@code includeIgnored} (-x). */
    public List<String> cleanPreview(boolean includeIgnored) { return indexOps.cleanPreview(includeIgnored); }

    /** Deletes the given untracked paths, ignored ones only when {@code includeIgnored}. Destructive. */
    public void clean(List<String> paths, boolean includeIgnored) { indexOps.clean(paths, includeIgnored); }

    /** Stages one hunk (or a subset of its lines) of an unstaged diff. */
    public void stageHunk(FileDiff diff, int hunkIndex, Set<Integer> lines) {
        indexOps.stageHunk(diff, hunkIndex, lines);
    }

    /** Unstages one hunk (or a subset of its lines) of a staged diff. */
    public void unstageHunk(FileDiff stagedDiff, int hunkIndex, Set<Integer> lines) {
        indexOps.unstageHunk(stagedDiff, hunkIndex, lines);
    }

    /** Discards one hunk (or a subset of its lines) from the working tree. Destructive. */
    public void discardHunk(FileDiff diff, int hunkIndex, Set<Integer> lines) {
        indexOps.discardHunk(diff, hunkIndex, lines);
    }

    public void applyPatch(String patch, boolean cached, boolean reverse) {
        indexOps.applyPatch(patch, cached, reverse);
    }

    public void commit(String message, boolean amend) {
        commitOps.commit(message, amend, null, null, false, false);
    }

    /**
     * @param author optional {@code --author} value ({@code Name <email>})
     * @param date   optional {@code --date} value
     * @param sign   true to sign with {@code -S}, using {@code user.signingkey} / GPG or SSH
     */
    public void commit(String message, boolean amend, String author, String date, boolean sign) {
        commitOps.commit(message, amend, author, date, sign, false);
    }

    /** @param noVerify skips the pre-commit and commit-msg hooks ({@code --no-verify}) */
    public void commit(String message, boolean amend, String author, String date, boolean sign, boolean noVerify) {
        commitOps.commit(message, amend, author, date, sign, noVerify);
    }

    /** Amends the last commit keeping its message. */
    public void amendNoEdit() { commitOps.amendNoEdit(false); }

    public void amendNoEdit(boolean noVerify) { commitOps.amendNoEdit(noVerify); }

    // ---------------------------------------------------------------- identity

    /** The author git would use, with the config level it comes from ({@code local}, {@code global}, {@code system} or {@code missing}). */
    public record Identity(String name, String email, String source) {
        public boolean known() { return !name.isBlank() && !email.isBlank(); }
        public String label() { return known() ? name + " <" + email + ">" : ""; }
    }

    public Identity identity() { return commitOps.identity(); }

    /** Sets the repository-local author, i.e. what will be used for the next commits in this clone. */
    public void setIdentity(String name, String email) { commitOps.setIdentity(name, email); }

    /** Value of {@code commit.gpgsign} (default false); a local setting overrides the global one. */
    public boolean signCommitDefault() { return commitOps.signCommitDefault(); }

    /** True when a pre-commit or commit-msg hook exists (in {@code core.hooksPath} when set). */
    public boolean commitHookPresent() { return commitOps.commitHookPresent(); }

    /** Contents of the configured {@code commit.template}, or null when unset or unreadable. */
    public String commitTemplate() { return commitOps.commitTemplate(); }

    // ---------------------------------------------------------------- patches

    /** Writes the mbox patch of one commit ({@code git format-patch -1 <rev> --stdout}) to {@code file}. */
    /** Packs loose objects and drops unreachable ones ({@code git gc}). */
    public void gc() { maintenance.gc(); }

    /** Prunes unreachable loose objects ({@code git prune}). */
    public void prune() { maintenance.prune(); }

    /** Checks connectivity and object validity ({@code git fsck}); returns its output. */
    public String fsck() { return maintenance.fsck(); }

    public void formatPatch(String rev, Path file) { patchOps.formatPatch(rev, file); }

    /** Writes one patch per commit of the range (from the merge base with {@code mergeBase}) into {@code dir}. */
    public void formatPatchRange(String a, String b, boolean mergeBase, Path dir) {
        patchOps.formatPatchRange(a, b, mergeBase, dir);
    }

    /** What {@code git apply --check --stat} says about a patch file. */
    public record PatchInfo(boolean mailbox, boolean applies, String stat, String error) {}

    /** Checks a patch against the working tree and tells whether it is a mailbox to apply with {@code git am}. */
    public PatchInfo patchInfo(Path file) { return patchOps.patchInfo(file); }

    /** Applies a plain diff with {@code git apply}; git refuses conflicting hunks instead of leaving markers. */
    public MergeOutcome applyPatchFile(Path file) { return patchOps.applyPatchFile(file); }

    /** Applies an mbox patch with {@code git am}; a failure leaves an AM operation for the banner. */
    public MergeOutcome applyMailPatch(Path file) { return patchOps.applyMailPatch(file); }

    // ---------------------------------------------------------------- branches, tags, history rewriting

    public void checkoutBranch(String name) { refsOps.checkoutBranch(name); }

    public void checkoutDetached(String rev) { refsOps.checkoutDetached(rev); }

    public void createBranch(String name, String startPoint, boolean checkout) {
        refsOps.createBranch(name, startPoint, checkout);
    }

    public void deleteBranch(String name, boolean force) { refsOps.deleteBranch(name, force); }

    /** @param message annotated tag message, or null for a lightweight tag */
    public void createTag(String name, String target, String message) { refsOps.createTag(name, target, message); }

    public void deleteTag(String name) { refsOps.deleteTag(name); }

    public MergeOutcome merge(String rev) {
        return merge(rev, false, false);
    }

    /** @param noFf forces a merge commit even when the branch could fast-forward */
    public MergeOutcome merge(String rev, boolean noFf) {
        return merge(rev, noFf, false);
    }

    /** @param squash stages the merged changes without committing ({@code --squash}) */
    public MergeOutcome merge(String rev, boolean noFf, boolean squash) {
        if (squash) return conflictAware(cmd("merge", "--squash", rev));
        List<String> a = new ArrayList<>(List.of("merge", "--no-edit"));
        if (noFf) a.add("--no-ff");
        a.add(rev);
        return conflictAware(cmd().args(a));
    }

    public void mergeAbort() {
        write(cmd("merge", "--abort"));
    }

    public MergeOutcome cherryPick(String rev) {
        return conflictAware(cmd("cherry-pick", rev));
    }

    public MergeOutcome revert(String rev) {
        return conflictAware(cmd("revert", "--no-edit", rev));
    }

    public void reset(String rev, ResetMode mode) {
        // Locale.ROOT: in Turkish "MIXED".toLowerCase() is not "mixed" but "mıxed", and git rejects the option.
        write(cmd("reset", "--" + mode.name().toLowerCase(java.util.Locale.ROOT), rev));
    }

    MergeOutcome conflictAware(GitCommand c) {
        GitResult r = writeRaw(c);
        if (r.exitCode() == 0) return MergeOutcome.DONE;
        if (status().hasConflicts()) return MergeOutcome.CONFLICTS;
        throw r.toException();
    }

    // ---------------------------------------------------------------- branch / tag / remote management

    public void renameBranch(String oldName, String newName) { refsOps.renameBranch(oldName, newName); }

    public void setUpstream(String branch, String upstream) { refsOps.setUpstream(branch, upstream); }

    public void unsetUpstream(String branch) { refsOps.unsetUpstream(branch); }

    public void pushTag(String remote, String tag, Consumer<GitProgress> progress, CancelToken cancel) {
        refsOps.pushTag(remote, tag, progress, cancel);
    }

    public void deleteRemoteTag(String remote, String tag, Consumer<GitProgress> progress, CancelToken cancel) {
        refsOps.deleteRemoteTag(remote, tag, progress, cancel);
    }

    public void deleteRemoteBranch(String remote, String branch, Consumer<GitProgress> progress, CancelToken cancel) {
        refsOps.deleteRemoteBranch(remote, branch, progress, cancel);
    }

    /** Remote name -> fetch URL, in git's order. */
    public java.util.Map<String, String> remoteUrls() { return refsOps.remoteUrls(); }

    public void addRemote(String name, String url) { refsOps.addRemote(name, url); }

    public void removeRemote(String name) { refsOps.removeRemote(name); }

    public void renameRemote(String oldName, String newName) { refsOps.renameRemote(oldName, newName); }

    public void setRemoteUrl(String name, String url) { refsOps.setRemoteUrl(name, url); }


    /** Folder name git would pick for a URL: "https://h/o/r.git" -> "r", "git@h:o/r.git" -> "r". */
    // ---------------------------------------------------------------- clone

    public static String repoNameFromUrl(String url) { return GitClone.repoNameFromUrl(url); }

    /**
     * Clones {@code url} into {@code parent/name}. On failure or cancellation a directory created by this call is
     * removed. Authentication is left to git (credential manager / ssh-agent): terminal prompts are disabled.
     */
    public static Path cloneRepository(GitRunner runner, String url, Path parent, String name,
                                       Consumer<GitProgress> progress, CancelToken cancel) {
        return GitClone.cloneRepository(runner, url, parent, name, progress, cancel);
    }


    // ---------------------------------------------------------------- commit tree / blobs

    /** Immediate children of {@code path} in {@code rev}'s tree (root when blank), folders first. */
    public List<TreeEntry> listTree(String rev, String path) {
        return objects.listTree(rev, path);
    }

    /**
     * Blob of one file at a commit.
     *
     * @param maxBytes files larger than this are reported as truncated without being read
     */
    public FileContent fileContent(String rev, String path, int maxBytes) {
        return objects.fileContent(rev, path, maxBytes);
    }

    /** Raw bytes of a blob, whatever its size. */
    public byte[] fileBytes(String rev, String path) {
        return objects.fileBytes(rev, path);
    }

    /** Writes the file as it was at {@code rev} to {@code target}. */
    public void saveFile(String rev, String path, Path target) {
        objects.saveFile(rev, path, target);
    }

    // ---------------------------------------------------------------- submodules


    public List<SubmoduleInfo> submodules() { return submodules(null); }

    /**
     * Submodules of the whole tree ({@code --recursive}); nested ones keep their path from the root.
     *
     * @param status the superproject status when it is already loaded: a submodule whose gitlink shows as
     *               modified has uncommitted changes inside, which {@code git submodule status} does not tell
     */
    public List<SubmoduleInfo> submodules(RepoStatus status) { return submoduleOps.list(status); }

    /** {@code git submodule update --init} for the given paths (all submodules when null or empty). */
    public void submoduleUpdate(List<String> paths, Consumer<GitProgress> progress, CancelToken cancel) {
        submoduleUpdate(paths, false, progress, cancel);
    }

    /** Same as above, also initialising and updating the submodules of the submodules with {@code --recursive}. */
    public void submoduleUpdate(List<String> paths, boolean recursive, Consumer<GitProgress> progress, CancelToken cancel) {
        submoduleOps.update(paths, recursive, progress, cancel);
    }

    /** Adds a submodule from {@code url} at {@code path}; the .gitmodules change is left staged. */
    public void submoduleAdd(String url, String path) { submoduleOps.add(url, path); }

    /** Removes a submodule: deinit, then {@code git rm} (the change is left staged). Destructive. */
    public void submoduleRemove(String path) { submoduleOps.remove(path); }

    /** True when {@code submodule.recurse} makes pull and checkout update the submodules. */
    public boolean submoduleRecurse() { return submoduleOps.recurse(); }

    public void setSubmoduleRecurse(boolean on) { submoduleOps.setRecurse(on); }

    // ---------------------------------------------------------------- worktrees

    public List<WorktreeInfo> worktrees() { return worktreeOps.list(); }

    /**
     * Adds a worktree at {@code path}. With {@code newBranch} a branch of that name is created at {@code startPoint};
     * otherwise {@code startPoint} (an existing branch or commit) is checked out.
     */
    public void worktreeAdd(Path path, String newBranch, String startPoint) {
        worktreeOps.add(path, newBranch, startPoint);
    }

    public void worktreeRemove(Path path, boolean force) { worktreeOps.remove(path, force); }

    /** Marks a worktree as locked (git then refuses to move or prune it); {@code reason} may be null. */
    public void worktreeLock(Path path, String reason) { worktreeOps.lock(path, reason); }

    public void worktreeUnlock(Path path) { worktreeOps.unlock(path); }

    /** Moves the worktree folder and its administrative files. */
    public void worktreeMove(Path from, Path to) { worktreeOps.move(from, to); }

    /** Drops the records of worktrees whose folder no longer exists. */
    public void worktreePrune() { worktreeOps.prune(); }

    /** True when this repository is a linked worktree of another one (not the main worktree). */
    public boolean isLinkedWorktree() { return worktreeOps.isLinked(); }

    // ---------------------------------------------------------------- commit relationships & rebase

    public boolean isAncestorOfHead(String hash) { return rebaseOps.isAncestorOfHead(hash); }

    /** True when the commit's changes are already in HEAD, either as an ancestor or as an equivalent cherry-pick. */
    public boolean isAlreadyInHead(String hash) { return rebaseOps.isAlreadyInHead(hash); }

    /** True when the commit is reachable from some remote-tracking branch (rewriting it needs a force push). */
    public boolean isOnRemote(String hash) { return rebaseOps.isOnRemote(hash); }

    /** Non-merge commits an interactive rebase starting at {@code from} would replay, oldest first. */
    public List<Commit> rebaseCandidates(Commit from) { return rebaseOps.rebaseCandidates(from); }

    /** Replays the current branch on top of {@code rev}. */
    public MergeOutcome rebaseOnto(String rev) { return rebaseOps.rebaseOnto(rev); }

    /**
     * Runs {@code git rebase -i} without opening an editor: the generated todo list replaces git's via
     * GIT_SEQUENCE_EDITOR, and rewords are done with an {@code exec git commit --amend -F} line.
     * A squash carrying a custom message becomes {@code fixup} plus the same amend: the changes are folded
     * into the group's commit as with squash, and the amend replaces git's combined message with that text.
     * Conflicts leave the rebase in progress.
     */
    public MergeOutcome rebaseInteractive(Commit from, List<RebaseStep> steps) {
        return rebaseOps.rebaseInteractive(from, steps);
    }

    /** Squashes the commits from {@code from} to HEAD into one with {@code message}; needs at least two commits. */
    public MergeOutcome squashFrom(Commit from, String message) {
        return rebaseOps.squashFrom(from, message);
    }

    // ---------------------------------------------------------------- starred commits

    /** Hashes of the commits the user starred in this repository, in insertion order. */
    public List<String> starredCommits() { return starredOps.starredCommits(); }

    /** Adds or removes a star; the list is kept in {@code .gcom/github/gitguiorg/gitgui/starred}, so it is per clone. */
    public void setStarred(String hash, boolean starred) { starredOps.setStarred(hash, starred); }

    /** The given commits in commit-date order (newest first), used by the "starred only" filter. */
    public List<Commit> logByHash(List<String> hashes) { return starredOps.logByHash(hashes); }

    // ---------------------------------------------------------------- search, blame & file history

    /**
     * History filtered by message, author, file, hash or changed content, optionally limited to one branch
     * ({@code "HEAD"} = current). Text is matched literally and case-insensitively; the content mode finds the
     * commits that added or removed the given string ({@code git log -S}).
     */
    public List<Commit> searchLog(LogFilter f, int skip, int limit) {
        return historyOps.search(f, skip, limit);
    }

    public List<BlameLine> blame(String path, String rev) { return historyOps.blame(path, rev); }

    /** Diff of a single file in a commit (against its first parent). */
    public List<FileDiff> diffCommitFile(String hash, String path, int context) {
        return diffCommitFile(hash, path, context, false);
    }

    /** ignoreWs: ignore whitespace differences (not suitable for building patches). */
    public List<FileDiff> diffCommitFile(String hash, String path, int context, boolean ignoreWs) {
        return diffCommitFile(hash, path, new DiffOptions(context, ignoreWs, false, false));
    }

    public List<FileDiff> diffCommitFile(String hash, String path, DiffOptions o) {
        return historyOps.diffCommitFile(hash, path, o);
    }

    // ---------------------------------------------------------------- conflicts & merge resolution

    GitResult readRaw(GitCommand c) {
        Lock l = lock.readLock();
        l.lock();
        try {
            return runner.runRaw(c.readOnly().timeout(READ_TIMEOUT));
        } finally {
            l.unlock();
        }
    }

    /** Base (stage 1), ours (stage 2) and theirs (stage 3) of a conflicted path; missing stages are null. */
    public ConflictVersions conflictVersions(String path) { return conflictOps.versions(path); }

    /** Writes the resolved content to the working tree and stages it. */
    public void resolveWithContent(String path, byte[] content) { conflictOps.resolveWithContent(path, content); }

    /** Resolves a conflicted path by taking one whole side, then stages it. */
    public void resolveTakeSide(String path, boolean ours) { conflictOps.takeSide(path, ours); }

    /** Resolves a conflicted path by deleting it. */
    public void resolveDelete(String path) { conflictOps.delete(path); }

    /** Replaces a working-tree file and stages it under the write lock (used while resolving conflicts). */
    void replaceFileAndStage(String path, byte[] content) {
        Lock l = lock.writeLock();
        l.lock();
        try {
            Files.write(root.resolve(path), content);
            runner.run(cmd("add", "--", path).timeout(WRITE_TIMEOUT));
        } catch (java.io.IOException e) {
            throw new GitException("cannot write " + path + ": " + e.getMessage(), e);
        } finally {
            l.unlock();
        }
    }

    // ---------------------------------------------------------------- ongoing operation

    /** Administrative git directory of this checkout; in a linked worktree it is under the main {@code .git}. */
    public Path gitDirPath() { return operationOps.gitDirPath(); }

    /** Shared git directory that holds refs and objects; differs from {@link #gitDirPath()} in a linked worktree. */
    public Path commonDirPath() { return operationOps.commonDirPath(); }

    /** The multi-step operation currently in progress, if any. */
    public Operation operation() { return operationOps.operation(); }

    /** Short name of the branch that receives the rebased commits, or null when no rebase is in progress. */
    public String rebaseBranch() { return operationOps.rebaseBranch(); }

    /** Short hash and subject of the commit a rebase is currently applying, or null. */
    public String rebaseIncoming() { return operationOps.rebaseIncoming(); }

    /** First line of MERGE_MSG while a merge is in progress, else null. */
    public String operationMessage() { return operationOps.operationMessage(); }

    public MergeOutcome operationContinue(Operation op) { return operationOps.operationContinue(op); }

    public void operationAbort(Operation op) { operationOps.operationAbort(op); }

    // ---------------------------------------------------------------- bisect

    /** State of a bisect in progress: the bad commit, the commits marked good and the remaining candidates. */
    public record BisectState(String bad, List<String> good, int candidates) {
        public boolean active() { return bad != null || !good.isEmpty(); }

        /** Rough number of further checks needed to find the first bad commit. */
        public int steps() {
            return candidates <= 1 ? 0 : 32 - Integer.numberOfLeadingZeros(candidates - 1);
        }
    }

    public BisectState bisectState() { return bisectOps.state(); }

    /** Starts a bisect when needed, then marks {@code rev} as good or bad. */
    public void bisectMark(String rev, boolean good) { bisectOps.mark(rev, good); }

    /** Tells bisect to skip the checked-out candidate. */
    public void bisectSkip() { bisectOps.skip(); }

    public void bisectReset() { bisectOps.reset(); }
    // ---------------------------------------------------------------- stash

    public List<StashEntry> stashList() { return stashOps.list(); }

    public void stashPush(String message, boolean includeUntracked) { stashOps.push(message, includeUntracked); }

    public MergeOutcome stashApply(String ref) { return stashOps.apply(ref); }

    public MergeOutcome stashPop(String ref) { return stashOps.pop(ref); }

    public void stashDrop(String ref) { stashOps.drop(ref); }

    /** Puts a dropped stash back, at the top of the list, from its commit hash. */
    public void stashStore(String hash, String message) { stashOps.store(hash, message); }

    public List<FileDiff> stashDiff(String ref, int context) {
        return stashDiff(ref, context, false);
    }

    /** ignoreWs: ignore whitespace differences (not suitable for building patches). */
    public List<FileDiff> stashDiff(String ref, int context, boolean ignoreWs) {
        return stashDiff(ref, new DiffOptions(context, ignoreWs, false, false));
    }

    public List<FileDiff> stashDiff(String ref, DiffOptions o) { return stashOps.diff(ref, o); }

    // ---------------------------------------------------------------- reflog

    /**
     * Reflog of a ref, newest first.
     *
     * @param ref a branch, tag or revision; null or blank means HEAD
     */
    public List<ReflogEntry> reflog(String ref, int limit) { return reflogOps.log(ref, limit); }

    // ---------------------------------------------------------------- network

    public void fetch(String remote, boolean prune, Consumer<GitProgress> progress, CancelToken cancel) {
        fetch(remote, prune, true, progress, cancel);
    }

    /** @param interactive false for background fetches that must never pop up a credentials prompt */
    public void fetch(String remote, boolean prune, boolean interactive, Consumer<GitProgress> progress, CancelToken cancel) {
        networkOps.fetch(remote, prune, interactive, progress, cancel);
    }

    public void pull(PullMode mode, Consumer<GitProgress> progress, CancelToken cancel) {
        networkOps.pull(mode, progress, cancel);
    }

    public void push(String remote, String branch, boolean setUpstream, boolean forceWithLease,
                     Consumer<GitProgress> progress, CancelToken cancel) {
        networkOps.push(remote, branch, setUpstream, forceWithLease, progress, cancel);
    }
}