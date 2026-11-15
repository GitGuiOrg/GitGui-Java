package com.github.gitguiorg.gitgui;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.gitguiorg.gitgui.git.GitClient;
import com.github.gitguiorg.gitgui.git.GitCommand;
import com.github.gitguiorg.gitgui.git.GitRunner;
import com.github.gitguiorg.gitgui.model.Commit;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IdentityTest {
    @TempDir Path tmp;
    GitRunner runner;
    Path dir;
    GitClient git;

    @BeforeEach
    void setUp() {
        runner = new GitRunner(null);
        dir = tmp.resolve("repo");
        git = GitClient.init(dir, runner);
        config("user.name", "Base Author");
        config("user.email", "base@example.com");
        config("commit.gpgsign", "false");
        config("core.autocrlf", "false");
        runner.run(GitCommand.in(dir).args("checkout", "-q", "-b", "main"));
    }

    void config(String k, String v) { runner.run(GitCommand.in(dir).args("config", "--local", k, v)); }

    void write(String rel, String content) throws IOException {
        Path p = dir.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    void commitAll(String msg) {
        git.stage(List.of("."));
        git.commit(msg, false);
    }

    @Test
    void identityComesFromTheRepositoryConfig() {
        GitClient.Identity id = git.identity();
        assertThat(id.known()).isTrue();
        assertThat(id.source()).isEqualTo("local");
        assertThat(id.name()).isEqualTo("Base Author");
        assertThat(id.email()).isEqualTo("base@example.com");
        assertThat(id.label()).isEqualTo("Base Author <base@example.com>");
    }

    @Test
    void setIdentityWritesTheLocalConfig() {
        git.setIdentity("New Name", "new@example.com");
        GitClient.Identity id = git.identity();
        assertThat(id.name()).isEqualTo("New Name");
        assertThat(id.email()).isEqualTo("new@example.com");
        assertThat(id.source()).isEqualTo("local");
        assertThat(runner.run(GitCommand.in(dir).args("config", "--local", "--get", "user.email")).out().strip())
                .isEqualTo("new@example.com");
    }

    @Test
    void commitKeepsTheAuthorAndDateOverride() throws Exception {
        write("f.txt", "one\n");
        commitAll("base");
        write("f.txt", "two\n");
        git.stage(List.of("f.txt"));
        git.commit("override", false, "Other Author <other@example.com>", "2020-01-02T03:04:05+0000", false);

        Commit c = git.log(0, 1, false).get(0);
        assertThat(c.subject()).isEqualTo("override");
        assertThat(c.authorName()).isEqualTo("Other Author");
        assertThat(c.authorEmail()).isEqualTo("other@example.com");
        assertThat(c.authorDate()).isEqualTo(Instant.parse("2020-01-02T03:04:05Z"));
    }

    @Test
    void unsignedCommitAndSigningDefault() throws Exception {
        write("f.txt", "one\n");
        commitAll("base");

        Commit c = git.log(0, 1, false).get(0);
        assertThat(c.signed()).isFalse();
        assertThat(c.signatureLabel()).isEqualTo("Not signed");
        assertThat(git.signCommitDefault()).isFalse();

        config("commit.gpgsign", "true");
        assertThat(git.signCommitDefault()).isTrue();
    }

    @Test
    void templateIsReadFromTheConfiguredPath() throws Exception {
        assertThat(git.commitTemplate()).isNull();

        write(".gitmessage", "Type: \n\n");
        config("commit.template", ".gitmessage");
        assertThat(git.commitTemplate()).isEqualTo("Type: \n\n");

        runner.run(GitCommand.in(dir).args("config", "--local", "--unset", "commit.template"));
        assertThat(git.commitTemplate()).isNull();
    }

    @Test
    void signatureLabelsDescribeTheGitState() {
        Commit good = new Commit("h", List.of(), "a", "a@x", Instant.EPOCH, List.of(), "s", "", "G");
        assertThat(good.signed()).isTrue();
        assertThat(good.signatureLabel()).contains("good signature");
        Commit bad = new Commit("h", List.of(), "a", "a@x", Instant.EPOCH, List.of(), "s", "", "B");
        assertThat(bad.signatureLabel()).contains("BAD");
    }
}
