package com.github.gitguiorg.gitgui.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ButClientTest {
    @TempDir Path tmp;

    /** A fake but inside a folder with a space, as the usual Windows install path. */
    private Path fakeBut() throws IOException {
        Path dir = tmp.resolve("Program Files").resolve("GitButler");
        Files.createDirectories(dir);
        Path exe = dir.resolve("but.exe");
        Files.writeString(exe, "");
        return exe.toAbsolutePath().normalize();
    }

    @Test
    void findsButWhenThePathEntryIsQuoted() throws Exception {
        Path exe = fakeBut();
        String path = "\"" + exe.getParent() + "\"" + File.pathSeparator + tmp.resolve("nope");

        assertThat(ButClient.locate(null, path)).isEqualTo(exe.toString());
    }

    @Test
    void anExplicitPathWinsAndIsChecked() throws Exception {
        Path exe = fakeBut();

        assertThat(ButClient.locate("\"" + exe + "\"", "")).isEqualTo(exe.toString());
        assertThat(ButClient.locate("  " + exe + "  ", null)).isEqualTo(exe.toString());
        assertThatThrownBy(() -> ButClient.locate(exe + ".bak", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void rootIsNormalized() throws Exception {
        Path exe = fakeBut();
        Files.createDirectory(tmp.resolve("repo"));
        ButClient client = ButClient.find(tmp.resolve("repo").resolve(".."), exe.toString());

        assertThat(client.root()).isEqualTo(tmp.toAbsolutePath().normalize());
    }

    @Test
    void missingRootIsRejected() throws Exception {
        Path exe = fakeBut();
        Path missing = tmp.resolve("missing");

        assertThatThrownBy(() -> ButClient.find(missing, exe.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("repository folder not found");
    }

    @Test
    void blankBranchNamesAreRejected() throws Exception {
        Path exe = fakeBut();
        ButClient client = ButClient.find(tmp, exe.toString());

        assertThatThrownBy(() -> client.applyBranch(" ", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("branch name is required");
        assertThatThrownBy(() -> client.unapplyBranch(null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("branch name is required");
        assertThatThrownBy(() -> client.pushStack(" ", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("branch name is required");
        assertThatThrownBy(() -> client.createBranch(" ", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("branch name is required");
        assertThatThrownBy(() -> client.createBranch("-base", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("branch name is invalid");
        assertThatThrownBy(() -> client.createBranchAbove(" ", "parent", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("branch name is required");
        assertThatThrownBy(() -> client.createBranchAbove("child", " ", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("branch name is required");
        assertThatThrownBy(() -> client.createBranchAbove("-child", "parent", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("branch name is invalid");
        assertThatThrownBy(() -> client.createBranchBelow(" ", "parent", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("branch name is required");
        assertThatThrownBy(() -> client.createBranchBelow("child", " ", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("branch name is required");
        assertThatThrownBy(() -> client.renameBranch(" ", "new-name", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("branch name is required");
        assertThatThrownBy(() -> client.renameBranch("old-name", " ", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("branch name is required");
        assertThatThrownBy(() -> client.renameBranch("old-name", "-new-name", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("branch name is invalid");
        assertThatThrownBy(() -> client.moveBranchAbove(" ", "target", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("branch name is required");
        assertThatThrownBy(() -> client.moveBranchAbove("branch", " ", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("branch name is required");
        assertThatThrownBy(() -> client.moveBranchAbove("branch", "branch", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("target branch must differ");
        assertThatThrownBy(() -> client.rewordCommit(" ", "message", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("commit id is required");
        assertThatThrownBy(() -> client.rewordCommit("commit", " ", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("commit message is required");
        assertThatThrownBy(() -> client.moveCommitToBranch(" ", "base", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("commit id is required");
        assertThatThrownBy(() -> client.moveCommitToBranch("commit", " ", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("branch name is required");
        assertThatThrownBy(() -> client.discardChange(" ", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("change path is required");
        assertThatThrownBy(() -> client.discardChanges(List.of(" "), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("change path is required");
        assertThatThrownBy(() -> client.discardChanges(List.of(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("change path is required");
        assertThatThrownBy(() -> client.commitChanges("branch", " ", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("commit message is required");
        assertThatThrownBy(() -> client.commitChanges("branch", "message", " ", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("change path is required");
        assertThatThrownBy(() -> client.commitChanges("branch", "message", List.of(" "), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("change path is required");
        assertThatThrownBy(() -> client.uncommit(null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("commit id is required");
        assertThatThrownBy(() -> client.pushBranch("", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("branch name is required");
        assertThatThrownBy(() -> client.updateBranch(null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("branch name is required");
    }

    @Test
    void parsesWorkspaceAndKeepsRawJsonOnFailure() {
        String json = "{\"uncommittedChanges\":[],\"stacks\":[],\"mergeBase\":null,"
                + "\"upstreamState\":{\"behind\":0}}";

        ButClient.WorkspaceStatus parsed = ButClient.parseWorkspace(json);
        assertThat(parsed.status()).isNotNull();
        assertThat(parsed.rawJson()).isEqualTo(json);
        assertThat(parsed.warning()).isNull();

        ButClient.WorkspaceStatus invalid = ButClient.parseWorkspace("not json");
        assertThat(invalid.status()).isNull();
        assertThat(invalid.rawJson()).isEqualTo("not json");
        assertThat(invalid.warning()).contains("invalid but status JSON");
    }
}
