package com.github.gitguiorg.gitgui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.gitguiorg.gitgui.git.GitClient;
import com.github.gitguiorg.gitgui.git.GitCommand;
import com.github.gitguiorg.gitgui.git.GitException;
import com.github.gitguiorg.gitgui.git.GitRunner;
import com.github.gitguiorg.gitgui.model.GitRef;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RefManagementTest {
    @TempDir Path tmp;
    GitRunner runner = new GitRunner(null);
    Path dir;
    Path bare;
    GitClient git;

    @BeforeEach
    void setUp() throws Exception {
        dir = tmp.resolve("repo");
        git = GitClient.init(dir, runner);
        for (String[] kv : new String[][]{{"user.name", "T"}, {"user.email", "t@x.it"}, {"commit.gpgsign", "false"}}) {
            runner.run(GitCommand.in(dir).args("config", kv[0], kv[1]));
        }
        runner.run(GitCommand.in(dir).args("checkout", "-q", "-b", "main"));
        Files.writeString(dir.resolve("a.txt"), "a");
        git.stage(List.of("a.txt"));
        git.commit("A", false);
        bare = tmp.resolve("remote.git");
        Files.createDirectories(bare);
        runner.run(GitCommand.in(bare).args("init", "--bare", "-q", "-b", "main"));
    }

    List<String> names() {
        return git.refs().stream().map(GitRef::shortName).toList();
    }

    String remoteRefs() {
        return runner.run(GitCommand.in(dir).args("ls-remote", "--heads", "--tags", "origin")).out();
    }

    @Test
    void renameAndDeleteBranch() throws Exception {
        git.createBranch("feature", "HEAD", true);
        Files.writeString(dir.resolve("b.txt"), "b");
        git.stage(List.of("b.txt"));
        git.commit("B", false);
        git.renameBranch("feature", "feat");
        assertThat(names()).contains("feat").doesNotContain("feature");

        git.checkoutBranch("main");
        assertThatThrownBy(() -> git.deleteBranch("feat", false))
                .isInstanceOfSatisfying(GitException.class, e -> assertThat(e.stderr()).contains("not fully merged"));
        git.deleteBranch("feat", true);
        assertThat(names()).doesNotContain("feat");
    }

    @Test
    void remoteManagement() {
        git.addRemote("origin", bare.toUri().toString());
        assertThat(git.remoteUrls()).containsEntry("origin", bare.toUri().toString());
        git.renameRemote("origin", "up");
        assertThat(git.remotes()).containsExactly("up");
        git.setRemoteUrl("up", "https://example.com/x.git");
        assertThat(git.remoteUrls()).containsEntry("up", "https://example.com/x.git");
        git.removeRemote("up");
        assertThat(git.remotes()).isEmpty();
        assertThat(git.remoteUrls()).isEmpty();
    }

    @Test
    void remoteUrlWithSpacesIsKeptWhole() {
        git.addRemote("origin", "https://example.com/my repo.git");
        assertThat(git.remoteUrls()).containsEntry("origin", "https://example.com/my repo.git");
    }

    @Test
    void pushAndDeleteTagsAndBranchesOnRemote() {
        git.addRemote("origin", bare.toUri().toString());
        git.push("origin", "main", true, false, null, null);

        git.createTag("v1", "HEAD", "release 1");
        git.pushTag("origin", "v1", null, null);
        assertThat(remoteRefs()).contains("refs/tags/v1");
        git.deleteRemoteTag("origin", "v1", null, null);
        assertThat(remoteRefs()).doesNotContain("refs/tags/v1");

        git.createBranch("topic", "HEAD", false);
        git.push("origin", "topic", false, false, null, null);
        assertThat(remoteRefs()).contains("refs/heads/topic");
        git.deleteRemoteBranch("origin", "topic", null, null);
        assertThat(remoteRefs()).doesNotContain("refs/heads/topic");
    }

    @Test
    void pushRefspecTargetsTheUpstreamBranch() {
        assertThat(GitRef.pushRefspec("fix", "origin/hotfix", List.of("origin"))).isEqualTo("fix:hotfix");
        assertThat(GitRef.pushRefspec("main", "origin/main", List.of("origin"))).isEqualTo("main:main");
        assertThat(GitRef.pushRefspec("x", "team/origin/dev", List.of("team/origin"))).isEqualTo("x:dev");
    }

    @Test
    void pushUpdatesTheUpstreamBranchWhenItsNameDiffers() throws Exception {
        git.addRemote("origin", bare.toUri().toString());
        git.push("origin", "main", true, false, null, null);
        runner.run(GitCommand.in(dir).args("push", "origin", "main:refs/heads/hotfix"));
        git.fetch("origin", true, null, null);
        runner.run(GitCommand.in(dir).args("checkout", "-q", "-b", "fix", "--track", "origin/hotfix"));
        Files.writeString(dir.resolve("b.txt"), "b");
        git.stage(List.of("b.txt"));
        git.commit("B", false);

        String refspec = GitRef.pushRefspec("fix", "origin/hotfix", git.remotes());
        git.push("origin", refspec, false, false, null, null);

        String refs = remoteRefs();
        assertThat(refs).contains("refs/heads/hotfix").doesNotContain("refs/heads/fix")
                .contains(git.status().headOid() + "\trefs/heads/hotfix");
    }

    @Test
    void reportsGitVersion() {
        assertThat(git.version()).startsWith("git version");
        assertThat(git.atLeast(1, 0)).isTrue();
    }

    @Test
    void remoteNameWithSlashIsNotCutAtFirstSlash() {
        git.addRemote("team/origin", bare.toUri().toString());
        git.push("team/origin", "main", true, false, null, null);
        git.fetch("team/origin", true, null, null);
        assertThat(names()).contains("team/origin/main");

        String remote = GitRef.remoteOf("team/origin/main", git.remotes());
        assertThat(remote).isEqualTo("team/origin");
        assertThat(GitRef.branchOf("team/origin/main", remote)).isEqualTo("main");
    }

    @Test
    void upstreamCanBeSetAndUnset() {
        git.addRemote("origin", bare.toUri().toString());
        git.push("origin", "main", false, false, null, null);
        git.fetch("origin", false, null, null);
        git.setUpstream("main", "origin/main");
        assertThat(git.refs()).filteredOn(r -> r.shortName().equals("main")).extracting(GitRef::upstream)
                .containsExactly("origin/main");
        git.unsetUpstream("main");
        assertThat(git.refs()).filteredOn(r -> r.shortName().equals("main")).extracting(GitRef::upstream)
                .containsOnlyNulls();
    }
}
