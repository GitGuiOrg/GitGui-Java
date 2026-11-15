package com.github.gitguiorg.gitgui.git;

import com.github.gitguiorg.gitgui.model.WorktreeInfo;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Worktree operations of a {@link GitClient}. */
final class GitWorktrees {
    private final GitClient git;

    GitWorktrees(GitClient git) {
        this.git = git;
    }

    List<WorktreeInfo> list() {
        List<WorktreeInfo> out = new ArrayList<>();
        String path = null, head = null, branch = null;
        boolean bare = false, locked = false, prunable = false;
        String[] lines = (git.read(git.cmd("worktree", "list", "--porcelain")).out() + "\n").split("\n", -1);
        for (String line : lines) {
            if (line.isBlank()) {
                if (path != null) out.add(new WorktreeInfo(path, head, branch, out.isEmpty(), bare, locked, prunable));
                path = head = branch = null;
                bare = locked = prunable = false;
            } else if (line.startsWith("worktree ")) {
                path = line.substring(9).strip();
            } else if (line.startsWith("HEAD ")) {
                head = line.substring(5).strip();
            } else if (line.startsWith("branch ")) {
                String b = line.substring(7).strip();
                branch = b.startsWith("refs/heads/") ? b.substring(11) : b;
            } else if (line.equals("bare")) {
                bare = true;
            } else if (line.startsWith("locked")) {
                locked = true;
            } else if (line.startsWith("prunable")) {
                prunable = true;
            }
        }
        return out;
    }

    /**
     * Adds a worktree at {@code path}. With {@code newBranch} a branch of that name is created at {@code startPoint};
     * otherwise {@code startPoint} (an existing branch or commit) is checked out.
     */
    void add(Path path, String newBranch, String startPoint) {
        List<String> a = new ArrayList<>(List.of("worktree", "add"));
        if (newBranch != null && !newBranch.isBlank()) {
            a.add("-b");
            a.add(newBranch);
        }
        a.add(path.toString());
        if (startPoint != null && !startPoint.isBlank()) a.add(startPoint);
        git.write(git.cmd().args(a));
    }

    void remove(Path path, boolean force) {
        List<String> a = new ArrayList<>(List.of("worktree", "remove"));
        if (force) a.add("--force");
        a.add(path.toString());
        git.write(git.cmd().args(a));
    }

    /** Marks a worktree as locked (git then refuses to move or prune it); {@code reason} may be null. */
    void lock(Path path, String reason) {
        List<String> a = new ArrayList<>(List.of("worktree", "lock"));
        if (reason != null && !reason.isBlank()) {
            a.add("--reason");
            a.add(reason.strip());
        }
        a.add(path.toString());
        git.write(git.cmd().args(a));
    }

    void unlock(Path path) { git.write(git.cmd("worktree", "unlock", path.toString())); }

    /** Moves the worktree folder and its administrative files. */
    void move(Path from, Path to) {
        git.write(git.cmd("worktree", "move", from.toString(), to.toString()));
    }

    /** Drops the records of worktrees whose folder no longer exists. */
    void prune() { git.write(git.cmd("worktree", "prune")); }

    /** True when this repository is a linked worktree of another one (not the main worktree). */
    boolean isLinked() {
        try {
            Path root = git.root();
            Path dir = root.resolve(git.read(git.cmd("rev-parse", "--git-dir")).out().strip()).toAbsolutePath().normalize();
            Path common = root.resolve(git.read(git.cmd("rev-parse", "--git-common-dir")).out().strip()).toAbsolutePath().normalize();
            return !dir.equals(common);
        } catch (GitException e) {
            return false;
        }
    }
}
