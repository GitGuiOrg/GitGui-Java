package com.github.gitguiorg.gitgui;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.gitguiorg.gitgui.git.GitClient;
import com.github.gitguiorg.gitgui.git.GitCommand;
import com.github.gitguiorg.gitgui.git.GitRunner;
import com.github.gitguiorg.gitgui.model.SubmoduleInfo;
import com.github.gitguiorg.gitgui.model.WorktreeInfo;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SubmoduleWorktreeTest {
    @TempDir Path tmp;
    GitRunner runner = new GitRunner(null);

    private GitClient repo(String name) throws Exception {
        Path dir = tmp.resolve(name);
        GitClient g = GitClient.init(dir, runner);
        for (String[] kv : new String[][]{{"user.name", "T"}, {"user.email", "t@x.it"}, {"commit.gpgsign", "false"},
                {"protocol.file.allow", "always"}}) {
            runner.run(GitCommand.in(dir).args("config", kv[0], kv[1]));
        }
        runner.run(GitCommand.in(dir).args("checkout", "-q", "-b", "main"));
        Files.writeString(dir.resolve("a.txt"), name);
        g.stage(List.of("a.txt"));
        g.commit("first " + name, false);
        return g;
    }

    @Test
    void submoduleLifecycle() throws Exception {
        GitClient lib = repo("lib");
        GitClient app = repo("app");
        assertThat(app.submodules()).isEmpty();
        runner.run(GitCommand.in(app.root()).args("-c", "protocol.file.allow=always", "submodule", "add", "-q", lib.root().toString(), "libs/lib"));
        app.commit("add submodule", false);

        assertThat(app.submodules()).singleElement().satisfies(s -> {
            assertThat(s.path()).isEqualTo("libs/lib");
            assertThat(s.state()).isEqualTo(SubmoduleInfo.State.CLEAN);
        });

        // A fresh clone has the submodule uninitialized until we update it.
        Path copy = tmp.resolve("copy");
        runner.run(GitCommand.in(tmp).args("clone", "-q", app.root().toString(), "copy"));
        runner.run(GitCommand.in(copy).args("config", "protocol.file.allow", "always"));
        GitClient c = GitClient.open(copy, runner);
        assertThat(c.submodules()).singleElement()
                .extracting(SubmoduleInfo::state).isEqualTo(SubmoduleInfo.State.UNINITIALIZED);
        // file:// submodule clones are blocked by git unless allowed globally, so initialise with an env override
        runner.run(GitCommand.in(copy).env("GIT_CONFIG_COUNT", "1").env("GIT_CONFIG_KEY_0", "protocol.file.allow")
                .env("GIT_CONFIG_VALUE_0", "always").args("submodule", "update", "--init"));
        c.submoduleUpdate(List.of("libs/lib"), null, null);   // already initialised: must be a no-op
        assertThat(c.submodules()).singleElement()
                .extracting(SubmoduleInfo::state).isEqualTo(SubmoduleInfo.State.CLEAN);
        assertThat(copy.resolve("libs/lib/a.txt")).exists();
    }

    @Test
    void worktreeLifecycle() throws Exception {
        GitClient g = repo("main-repo");
        List<WorktreeInfo> before = g.worktrees();
        assertThat(before).hasSize(1);
        assertThat(before.get(0).main()).isTrue();
        assertThat(before.get(0).branch()).isEqualTo("main");

        Path wt = tmp.resolve("wt with space");
        g.worktreeAdd(wt, "feature/x", "HEAD");
        List<WorktreeInfo> after = g.worktrees();
        assertThat(after).hasSize(2);
        WorktreeInfo added = after.get(1);
        assertThat(added.main()).isFalse();
        assertThat(added.branch()).isEqualTo("feature/x");
        assertThat(Files.isSameFile(Path.of(added.path()), wt)).isTrue();
        assertThat(wt.resolve("a.txt")).exists();

        g.worktreeRemove(wt, false);
        assertThat(g.worktrees()).hasSize(1);
        assertThat(wt).doesNotExist();
    }

    @Test
    void worktreeLockMoveAndPrune() throws Exception {
        GitClient g = repo("wt-repo");
        Path first = tmp.resolve("wt-a");
        Path moved = tmp.resolve("wt b");
        g.worktreeAdd(first, "lock-me", "HEAD");

        g.worktreeLock(first, "testing");
        assertThat(byBranch(g, "lock-me")).satisfies(w -> {
            assertThat(w.locked()).isTrue();
            assertThat(w.label()).contains("locked");
        });
        g.worktreeUnlock(first);
        assertThat(byBranch(g, "lock-me").locked()).isFalse();

        g.worktreeMove(first, moved);
        assertThat(first).doesNotExist();
        assertThat(Files.isSameFile(Path.of(byBranch(g, "lock-me").path()), moved)).isTrue();
        assertThat(moved.resolve("a.txt")).exists();

        deleteTree(moved);
        assertThat(byBranch(g, "lock-me").prunable()).isTrue();
        g.worktreePrune();
        assertThat(g.worktrees()).hasSize(1);
    }

    @Test
    void linkedWorktreeIsDetected() throws Exception {
        GitClient main = repo("linked-main");
        Path wt = tmp.resolve("linked-wt");
        main.worktreeAdd(wt, "side", "HEAD");
        assertThat(main.isLinkedWorktree()).isFalse();
        assertThat(GitClient.open(wt, runner).isLinkedWorktree()).isTrue();
    }

    private static WorktreeInfo byBranch(GitClient g, String branch) {
        return g.worktrees().stream().filter(w -> branch.equals(w.branch())).findFirst().orElseThrow();
    }

    private static void deleteTree(Path dir) throws Exception {
        try (var s = Files.walk(dir)) {
            s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    @Test
    void askpassBridgeAnswersPrompts() {
        List<String> seen = new java.util.ArrayList<>();
        GitRunner r = new GitRunner(null).setPrompter((prompt, secret) -> {
            seen.add(prompt + (secret ? " [secret]" : ""));
            return java.util.Optional.of(secret ? "s3cret" : "bob");
        });
        var res = r.run(GitCommand.in(tmp).interactive()
                .args("-c", "credential.helper=", "credential", "fill")
                .stdin("protocol=https\nhost=example.invalid\n\n"));
        assertThat(res.out()).contains("username=bob").contains("password=s3cret");
        assertThat(seen).hasSize(2);
        assertThat(seen.get(0)).startsWith("Username");
        assertThat(seen.get(1)).contains("Password").endsWith("[secret]");
    }

    @Test
    void askpassCancelFailsTheCommand() {
        GitRunner r = new GitRunner(null).setPrompter((prompt, secret) -> java.util.Optional.empty());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> r.run(GitCommand.in(tmp).interactive()
                .args("-c", "credential.helper=", "credential", "fill")
                .stdin("protocol=https\nhost=example.invalid\n\n")))
                .isInstanceOf(com.github.gitguiorg.gitgui.git.GitException.class);
    }
}
