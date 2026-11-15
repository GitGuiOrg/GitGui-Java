package com.github.gitguiorg.gitgui;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.gitguiorg.gitgui.git.GitClient;
import com.github.gitguiorg.gitgui.git.GitCommand;
import com.github.gitguiorg.gitgui.git.GitRunner;
import com.github.gitguiorg.gitgui.model.SubmoduleInfo;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SubmoduleTreeTest {
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
        Files.writeString(dir.resolve("x.txt"), name);
        g.stage(List.of("x.txt"));
        g.commit("first " + name, false);
        return g;
    }

    @Test
    void nestedSubmodulesAreListedRecursivelyAndFlagUncommittedChanges() throws Exception {
        GitClient leaf = repo("leaf");
        GitClient mid = repo("mid");
        runner.run(GitCommand.in(mid.root()).args("-c", "protocol.file.allow=always", "submodule", "add", "-q",
                leaf.root().toString(), "nested"));
        mid.stage(List.of("."));
        mid.commit("add nested", false);

        GitClient app = repo("app");
        // the file transport needs an explicit override for submodules, so the fixture adds it directly
        runner.run(GitCommand.in(app.root()).args("-c", "protocol.file.allow=always", "submodule", "add", "-q",
                mid.root().toString(), "libs/mid"));
        app.stage(List.of("."));
        app.commit("add submodule", false);
        assertThat(app.submodules()).extracting(SubmoduleInfo::path)
                .containsExactly("libs/mid", "libs/mid/nested");
        assertThat(app.submodules()).filteredOn(s -> s.path().equals("libs/mid")).singleElement()
                .satisfies(s -> assertThat(s.state()).isEqualTo(SubmoduleInfo.State.CLEAN));

        Path copy = tmp.resolve("copy");
        runner.run(GitCommand.in(tmp).args("clone", "-q", app.root().toString(), "copy"));
        runner.run(GitCommand.in(copy).args("config", "protocol.file.allow", "always"));
        GitClient c = GitClient.open(copy, runner);
        assertThat(c.submodules()).allSatisfy(s -> assertThat(s.state()).isEqualTo(SubmoduleInfo.State.UNINITIALIZED));

        // --recursive also initialises the submodule of the submodule
        runner.run(GitCommand.in(copy).env("GIT_CONFIG_COUNT", "1").env("GIT_CONFIG_KEY_0", "protocol.file.allow")
                .env("GIT_CONFIG_VALUE_0", "always").args("submodule", "update", "--init", "--recursive"));
        assertThat(c.submodules()).extracting(SubmoduleInfo::path)
                .containsExactly("libs/mid", "libs/mid/nested");
        assertThat(c.submodules()).allSatisfy(s -> assertThat(s.state()).isEqualTo(SubmoduleInfo.State.CLEAN));

        Files.writeString(copy.resolve("libs/mid/nested/x.txt"), "changed");
        assertThat(c.submodules()).filteredOn(s -> s.path().equals("libs/mid/nested")).singleElement().satisfies(s -> {
            assertThat(s.dirty()).isTrue();
            assertThat(s.statusText()).isEqualTo("uncommitted changes");
        });
        assertThat(c.submodules()).filteredOn(s -> s.path().equals("libs/mid")).singleElement()
                .satisfies(s -> assertThat(s.dirty()).isTrue());
    }

    @Test
    void removeSubmoduleDeinitialisesAndUnstagesIt() throws Exception {
        GitClient lib = repo("lib2");
        GitClient app = repo("app2");
        runner.run(GitCommand.in(app.root()).args("-c", "protocol.file.allow=always", "submodule", "add", "-q",
                lib.root().toString(), "libs/lib"));
        app.stage(List.of("."));
        app.commit("add submodule", false);
        assertThat(app.submodules()).singleElement().satisfies(s -> {
            assertThat(s.path()).isEqualTo("libs/lib");
            assertThat(s.dirty()).isFalse();
        });

        app.submoduleRemove("libs/lib");
        assertThat(app.root().resolve("libs/lib")).doesNotExist();
        assertThat(app.submodules()).isEmpty();
    }

    @Test
    void submoduleRecurseIsStoredInTheRepositoryConfig() throws Exception {
        GitClient app = repo("recurse");
        assertThat(app.submoduleRecurse()).isFalse();
        app.setSubmoduleRecurse(true);
        assertThat(app.submoduleRecurse()).isTrue();
        assertThat(app.submodules()).isEmpty();
        app.setSubmoduleRecurse(false);
        assertThat(app.submoduleRecurse()).isFalse();
    }
}
