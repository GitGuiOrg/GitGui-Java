package com.github.gitguiorg.gitgui.git;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Commit creation, identity and commit-related configuration of a {@link GitClient}. */
final class GitCommits {
    private final GitClient git;

    GitCommits(GitClient git) {
        this.git = git;
    }

    /**
     * @param author optional {@code --author} value ({@code Name <email>})
     * @param date   optional {@code --date} value
     * @param sign   true to sign with {@code -S}, using {@code user.signingkey} / GPG or SSH
     */
    void commit(String message, boolean amend, String author, String date, boolean sign, boolean noVerify) {
        List<String> a = new ArrayList<>(List.of("commit", "-F", "-"));
        if (amend) a.add("--amend");
        if (sign) a.add("-S");
        if (noVerify) a.add("--no-verify");
        if (author != null && !author.isBlank()) a.add("--author=" + author);
        if (date != null && !date.isBlank()) a.add("--date=" + date);
        GitCommand c = git.cmd().args(a).stdin(message);
        // a signature may need a passphrase: let ssh-keygen ask through the same bridge as the network prompts
        if (sign) c.interactive();
        git.write(c);
    }

    void amendNoEdit(boolean noVerify) {
        git.write(noVerify ? git.cmd("commit", "--amend", "--no-edit", "--no-verify")
                : git.cmd("commit", "--amend", "--no-edit"));
    }

    GitClient.Identity identity() {
        String name = configValue("user.name");
        String email = configValue("user.email");
        String source = "missing";
        for (String level : List.of("local", "global", "system")) {
            if (!configValue(level, "user.name").isBlank() || !configValue(level, "user.email").isBlank()) {
                source = level;
                break;
            }
        }
        return new GitClient.Identity(name, email, source);
    }

    /** Sets the repository-local author, i.e. what will be used for the next commits in this clone. */
    void setIdentity(String name, String email) {
        git.write(git.cmd("config", "--local", "user.name", name));
        git.write(git.cmd("config", "--local", "user.email", email));
    }

    /** Value of {@code commit.gpgsign} (default false); a local setting overrides the global one. */
    boolean signCommitDefault() {
        GitResult r = git.read(git.cmd("config", "--bool", "commit.gpgsign").allowExit(1));
        return r.exitCode() == 0 && r.out().strip().equals("true");
    }

    /** True when a pre-commit or commit-msg hook exists (in {@code core.hooksPath} when set). */
    boolean commitHookPresent() {
        // --git-path resolves core.hooksPath and, in a linked worktree, the hooks of the shared common dir.
        Path dir = Path.of(git.read(git.cmd("rev-parse", "--git-path", "hooks")).out().strip());
        if (!dir.isAbsolute()) dir = git.root().resolve(dir).normalize();
        return Files.isRegularFile(dir.resolve("pre-commit")) || Files.isRegularFile(dir.resolve("commit-msg"));
    }

    /** Contents of the configured {@code commit.template}, or null when unset or unreadable. */
    String commitTemplate() {
        String p = configValue("commit.template");
        if (p.isBlank()) return null;
        Path file = Path.of(p);
        if (!file.isAbsolute()) file = git.root().resolve(file);
        try {
            return Files.isRegularFile(file) ? Files.readString(file) : null;
        } catch (java.io.IOException e) {
            return null;
        }
    }

    private String configValue(String key) {
        return configValue(null, key);
    }

    /** Config value at one level ({@code --local}, {@code --global}, ...) or the effective one when {@code level} is null. */
    private String configValue(String level, String key) {
        List<String> a = new ArrayList<>(List.of("config"));
        if (level != null) a.add("--" + level);
        a.add("--get");
        a.add(key);
        GitResult r = git.read(git.cmd().args(a).allowExit(1));
        return r.exitCode() == 0 ? r.out().strip() : "";
    }
}
