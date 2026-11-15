package com.github.gitguiorg.gitgui;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.gitguiorg.gitgui.git.GitClient;
import com.github.gitguiorg.gitgui.git.GitCommand;
import com.github.gitguiorg.gitgui.git.GitRunner;
import com.github.gitguiorg.gitgui.model.*;
import com.github.gitguiorg.gitgui.model.ConflictMarkers.Choice;
import com.github.gitguiorg.gitgui.model.Merge3.Chunk;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConflictTest {
    @TempDir Path tmp;

    static List<String> l(String... s) { return List.of(s); }

    @Test
    void disjointChangesMergeCleanly() {
        List<Chunk> c = Merge3.merge(l("a", "b", "c", "d", "e"), l("a", "B", "c", "d", "e"), l("a", "b", "c", "d", "E"));
        assertThat(c).noneMatch(Chunk::conflict);
        assertThat(Merge3.toMarkedLines(c)).containsExactly("a", "B", "c", "d", "E");
    }

    @Test
    void sameLineChangedDifferentlyConflicts() {
        List<Chunk> c = Merge3.merge(l("a", "b", "c"), l("a", "X", "c"), l("a", "Y", "c"));
        assertThat(c).filteredOn(Chunk::conflict).hasSize(1);
        Chunk k = c.stream().filter(Chunk::conflict).findFirst().orElseThrow();
        assertThat(k.ours()).containsExactly("X");
        assertThat(k.theirs()).containsExactly("Y");
        assertThat(k.base()).containsExactly("b");
        assertThat(k.oursStart()).isEqualTo(1);
    }

    @Test
    void identicalChangesAreNotConflicts() {
        List<Chunk> c = Merge3.merge(l("a", "b"), l("a", "Z"), l("a", "Z"));
        assertThat(c).noneMatch(Chunk::conflict);
        assertThat(Merge3.toMarkedLines(c)).containsExactly("a", "Z");
    }

    @Test
    void insertionsAndDeletions() {
        assertThat(Merge3.toMarkedLines(Merge3.merge(l("a", "b", "c"), l("a", "c"), l("a", "b", "c", "d"))))
                .containsExactly("a", "c", "d");
        List<Chunk> addAdd = Merge3.merge(l(), l("x"), l("y"));
        assertThat(addAdd).filteredOn(Chunk::conflict).hasSize(1);
    }

    @Test
    void markersRoundTripAndResolve() {
        List<String> marked = Merge3.toMarkedLines(Merge3.merge(l("a", "b", "c"), l("a", "X", "c"), l("a", "Y", "c")));
        var blocks = ConflictMarkers.find(marked);
        assertThat(blocks).hasSize(1);
        assertThat(ConflictMarkers.resolve(marked, blocks.get(0), Choice.OURS)).containsExactly("a", "X", "c");
        assertThat(ConflictMarkers.resolve(marked, blocks.get(0), Choice.THEIRS)).containsExactly("a", "Y", "c");
        assertThat(ConflictMarkers.resolve(marked, blocks.get(0), Choice.BASE)).containsExactly("a", "b", "c");
        assertThat(ConflictMarkers.resolve(marked, blocks.get(0), Choice.OURS_THEN_THEIRS)).containsExactly("a", "X", "Y", "c");
        assertThat(ConflictMarkers.resolve(marked, blocks.get(0), Choice.THEIRS_THEN_OURS)).containsExactly("a", "Y", "X", "c");
        assertThat(ConflictMarkers.find(ConflictMarkers.resolve(marked, blocks.get(0), Choice.OURS))).isEmpty();
    }

    @Test
    void textHelpers() {
        assertThat(Merge3.lines("a\r\nb\r\n")).containsExactly("a", "b");
        assertThat(Merge3.lines("")).isEmpty();
        assertThat(Merge3.eolOf("a\r\nb")).isEqualTo("\r\n");
        assertThat(Merge3.eolOf("a\nb")).isEqualTo("\n");
    }

    // ---------------------------------------------------------------- real git

    GitRunner runner = new GitRunner(null);
    Path dir;
    GitClient git;

    void setupConflict() throws Exception {
        dir = tmp.resolve("repo");
        git = GitClient.init(dir, runner);
        for (String[] kv : new String[][]{{"user.name", "T"}, {"user.email", "t@x.it"}, {"commit.gpgsign", "false"},
                {"core.autocrlf", "false"}}) {
            runner.run(GitCommand.in(dir).args("config", kv[0], kv[1]));
        }
        runner.run(GitCommand.in(dir).args("checkout", "-q", "-b", "main"));
        Files.writeString(dir.resolve("a.txt"), "l1\nbase\nl3\n");
        git.stage(List.of("a.txt"));
        git.commit("init", false);
        git.createBranch("other", "HEAD", true);
        Files.writeString(dir.resolve("a.txt"), "l1\nother\nl3\n");
        git.stage(List.of("a.txt"));
        git.commit("other", false);
        git.checkoutBranch("main");
        Files.writeString(dir.resolve("a.txt"), "l1\nmain\nl3\n");
        git.stage(List.of("a.txt"));
        git.commit("main", false);
        assertThat(git.merge("other")).isEqualTo(MergeOutcome.CONFLICTS);
    }

    @Test
    void resolveAndContinueMerge() throws Exception {
        setupConflict();
        assertThat(git.operation()).isEqualTo(Operation.MERGE);

        ConflictVersions v = git.conflictVersions("a.txt");
        assertThat(new String(v.base(), StandardCharsets.UTF_8)).isEqualTo("l1\nbase\nl3\n");
        assertThat(new String(v.ours(), StandardCharsets.UTF_8)).isEqualTo("l1\nmain\nl3\n");
        assertThat(new String(v.theirs(), StandardCharsets.UTF_8)).isEqualTo("l1\nother\nl3\n");
        assertThat(v.isBinary()).isFalse();

        var marked = Merge3.toMarkedLines(Merge3.merge(Merge3.lines("l1\nbase\nl3\n"), Merge3.lines("l1\nmain\nl3\n"),
                Merge3.lines("l1\nother\nl3\n")));
        var resolved = ConflictMarkers.resolve(marked, ConflictMarkers.find(marked).get(0), Choice.OURS_THEN_THEIRS);
        git.resolveWithContent("a.txt", (String.join("\n", resolved) + "\n").getBytes(StandardCharsets.UTF_8));
        assertThat(git.status().hasConflicts()).isFalse();

        assertThat(git.operationContinue(Operation.MERGE)).isEqualTo(MergeOutcome.DONE);
        assertThat(git.operation()).isEqualTo(Operation.NONE);
        assertThat(Files.readString(dir.resolve("a.txt"))).isEqualTo("l1\nmain\nother\nl3\n");
        assertThat(git.log(0, 1, false).get(0).isMerge()).isTrue();
    }

    @Test
    void takeSideAndAbort() throws Exception {
        setupConflict();
        git.resolveTakeSide("a.txt", false);
        assertThat(Files.readString(dir.resolve("a.txt"))).isEqualTo("l1\nother\nl3\n");
        assertThat(git.status().hasConflicts()).isFalse();
        git.operationAbort(Operation.MERGE);
        assertThat(git.operation()).isEqualTo(Operation.NONE);
        assertThat(Files.readString(dir.resolve("a.txt"))).isEqualTo("l1\nmain\nl3\n");
    }

    @Test
    void cherryPickConflictIsRecognised() throws Exception {
        setupConflict();
        git.operationAbort(Operation.MERGE);
        String other = git.log(0, 10, true).stream().filter(c -> c.subject().equals("other")).findFirst().orElseThrow().hash();
        assertThat(git.cherryPick(other)).isEqualTo(MergeOutcome.CONFLICTS);
        assertThat(git.operation()).isEqualTo(Operation.CHERRY_PICK);
        git.operationAbort(Operation.CHERRY_PICK);
        assertThat(git.operation()).isEqualTo(Operation.NONE);
    }
}
