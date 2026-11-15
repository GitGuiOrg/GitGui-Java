package com.github.gitguiorg.gitgui.git;

import com.github.gitguiorg.gitgui.model.GitProgress;
import com.github.gitguiorg.gitgui.model.RepoStatus;
import com.github.gitguiorg.gitgui.model.StatusEntry;
import com.github.gitguiorg.gitgui.model.SubmoduleInfo;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Submodule operations of a {@link GitClient}. */
final class GitSubmodules {
    private static final Pattern LINE = Pattern.compile("^([ +\\-U])([0-9a-f]+) (.*?)(?: \\((.*)\\))?$");

    private final GitClient git;

    GitSubmodules(GitClient git) {
        this.git = git;
    }

    List<SubmoduleInfo> list(RepoStatus status) {
        Path root = git.root();
        if (!Files.exists(root.resolve(".gitmodules"))) return List.of();
        Set<String> dirty = new HashSet<>();
        for (StatusEntry e : (status != null ? status : git.status(false)).entries()) {
            if ((e.kind() == StatusEntry.Kind.ORDINARY || e.kind() == StatusEntry.Kind.UNMERGED)
                    && e.worktreeState() == 'M') dirty.add(e.path());
        }
        List<SubmoduleInfo> out = new ArrayList<>();
        for (String line : git.read(git.cmd("submodule", "status", "--recursive")).out().split("\n")) {
            if (line.isBlank()) continue;
            var m = LINE.matcher(line.stripTrailing());
            if (!m.matches()) continue;
            SubmoduleInfo.State st = switch (m.group(1).charAt(0)) {
                case '-' -> SubmoduleInfo.State.UNINITIALIZED;
                case '+' -> SubmoduleInfo.State.MODIFIED;
                case 'U' -> SubmoduleInfo.State.CONFLICT;
                default -> SubmoduleInfo.State.CLEAN;
            };
            String path = m.group(3);
            boolean changed = dirty.contains(path) || (st == SubmoduleInfo.State.CLEAN && dirty(path));
            out.add(new SubmoduleInfo(path, m.group(2), st, m.group(4), changed));
        }
        return out;
    }

    /** Uncommitted tracked changes inside a checked-out submodule; the superproject status only flags its parent. */
    private boolean dirty(String path) {
        Path dir = git.root().resolve(path);
        if (!Files.isDirectory(dir)) return false;
        GitResult r = git.readRaw(GitCommand.in(dir).args("status", "--porcelain", "--untracked-files=no"));
        return r.exitCode() == 0 && !r.out().isBlank();
    }

    void update(List<String> paths, boolean recursive, Consumer<GitProgress> progress, CancelToken cancel) {
        List<String> a = new ArrayList<>(List.of("submodule", "update", "--init", "--progress"));
        if (recursive) a.add("--recursive");
        if (paths != null && !paths.isEmpty()) {
            a.add("--");
            a.addAll(paths);
        }
        git.network().run(git.cmd().args(a), progress, cancel, true, true);
    }

    /** Adds a submodule from {@code url} at {@code path}; the .gitmodules change is left staged. */
    void add(String url, String path) {
        git.network().run(git.cmd("submodule", "add", "--", url, path), null, null, true, true);
    }

    /** Removes a submodule: deinit, then {@code git rm} (the change is left staged). Destructive. */
    void remove(String path) {
        git.write(git.cmd("submodule", "deinit", "-f", "--", path));
        git.write(git.cmd("rm", "-f", "--", path));
    }

    /** True when {@code submodule.recurse} makes pull and checkout update the submodules. */
    boolean recurse() {
        GitResult r = git.read(git.cmd("config", "--bool", "submodule.recurse").allowExit(1));
        return r.exitCode() == 0 && "true".equals(r.out().strip());
    }

    void setRecurse(boolean on) {
        if (on) git.write(git.cmd("config", "submodule.recurse", "true"));
        else git.write(git.cmd("config", "--unset", "submodule.recurse").allowExit(5));
    }
}
