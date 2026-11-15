package com.github.gitguiorg.gitgui.git;

import com.github.gitguiorg.gitgui.model.GitProgress;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Branch, tag and remote management of a {@link GitClient}. */
final class GitRefs {
    private final GitClient git;

    GitRefs(GitClient git) {
        this.git = git;
    }

    void checkoutBranch(String name) {
        git.write(git.cmd("switch", name));
    }

    void checkoutDetached(String rev) {
        git.write(git.cmd("switch", "--detach", rev));
    }

    void createBranch(String name, String startPoint, boolean checkout) {
        if (checkout) git.write(git.cmd("switch", "-c", name, startPoint));
        else git.write(git.cmd("branch", name, startPoint));
    }

    void deleteBranch(String name, boolean force) {
        git.write(git.cmd("branch", force ? "-D" : "-d", name));
    }

    /** @param message annotated tag message, or null for a lightweight tag */
    void createTag(String name, String target, String message) {
        if (message == null) git.write(git.cmd("tag", name, target));
        else git.write(git.cmd("tag", "-a", name, "-F", "-", target).stdin(message));
    }

    void deleteTag(String name) {
        git.write(git.cmd("tag", "-d", name));
    }

    void renameBranch(String oldName, String newName) {
        git.write(git.cmd("branch", "-m", oldName, newName));
    }

    void setUpstream(String branch, String upstream) {
        git.write(git.cmd("branch", "--set-upstream-to=" + upstream, branch));
    }

    void unsetUpstream(String branch) {
        git.write(git.cmd("branch", "--unset-upstream", branch));
    }

    void pushTag(String remote, String tag, Consumer<GitProgress> progress, CancelToken cancel) {
        git.network().run(git.cmd("push", "--progress", remote, "refs/tags/" + tag), progress, cancel, false);
    }

    void deleteRemoteTag(String remote, String tag, Consumer<GitProgress> progress, CancelToken cancel) {
        git.network().run(git.cmd("push", "--progress", remote, "--delete", "refs/tags/" + tag), progress, cancel, false);
    }

    void deleteRemoteBranch(String remote, String branch, Consumer<GitProgress> progress, CancelToken cancel) {
        git.network().run(git.cmd("push", "--progress", remote, "--delete", branch), progress, cancel, false);
    }

    /** Remote name -> fetch URL, in git's order. */
    Map<String, String> remoteUrls() {
        Map<String, String> m = new LinkedHashMap<>();
        for (String line : git.read(git.cmd("remote", "-v")).out().split("\\R")) {
            String t = line.stripTrailing();
            String suffix = " (fetch)";
            if (!t.endsWith(suffix)) continue;
            // git separates the name and the URL with a tab; splitting on blanks would cut URLs with spaces.
            String body = t.substring(0, t.length() - suffix.length());
            int sep = body.indexOf('\t');
            if (sep < 0) sep = body.indexOf(' ');
            if (sep <= 0) continue;
            String url = body.substring(sep + 1).strip();
            if (!url.isEmpty()) m.put(body.substring(0, sep).strip(), url);
        }
        return m;
    }

    void addRemote(String name, String url) { git.write(git.cmd("remote", "add", name, url)); }

    void removeRemote(String name) { git.write(git.cmd("remote", "remove", name)); }

    void renameRemote(String oldName, String newName) { git.write(git.cmd("remote", "rename", oldName, newName)); }

    void setRemoteUrl(String name, String url) { git.write(git.cmd("remote", "set-url", name, url)); }
}
