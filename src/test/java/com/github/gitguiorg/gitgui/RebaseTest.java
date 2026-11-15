package com.github.gitguiorg.gitgui;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.gitguiorg.gitgui.git.GitClient;
import com.github.gitguiorg.gitgui.git.GitCommand;
import com.github.gitguiorg.gitgui.git.GitRunner;
import com.github.gitguiorg.gitgui.model.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RebaseTest {
    @TempDir Path tmp;
    GitRunner runner = new GitRunner(null);
    Path dir;
    GitClient git;

    @BeforeEach
    void setUp() {
        dir = tmp.resolve("repo with space");
        git = GitClient.init(dir, runner);
        for (String[] kv : new String[][]{{"user.name", "T"}, {"user.email", "t@x.it"}, {"commit.gpgsign", "false"},
                {"core.autocrlf", "false"}}) {
            runner.run(GitCommand.in(dir).args("config", kv[0], kv[1]));
        }
        runner.run(GitCommand.in(dir).args("checkout", "-q", "-b", "main"));
    }

    void commitFile(String name, String content, String message) throws Exception {
        Files.writeString(dir.resolve(name), content);
        git.stage(List.of(name));
        git.commit(message, false);
    }

    List<String> subjects() {
        return git.log(0, 50, false).stream().map(Commit::subject).toList();
    }

    Commit byMessage(String subject) {
        return git.log(0, 50, false).stream().filter(c -> c.subject().equals(subject)).findFirst().orElseThrow();
    }

    List<RebaseStep> steps(Object... spec) {
        List<RebaseStep> out = new ArrayList<>();
        for (int i = 0; i < spec.length; i += 2) {
            Commit c = byMessage((String) spec[i]);
            RebaseAction a = (RebaseAction) spec[i + 1];
            out.add(new RebaseStep(a, c.hash(), c.subject(), a == RebaseAction.REWORD ? "Reworded " + c.subject() + "\n\nnew body" : null));
        }
        return out;
    }

    @Test
    void rewordReorderSquashAndDrop() throws Exception {
        commitFile("a.txt", "a", "A");
        commitFile("b.txt", "b", "B");
        commitFile("c.txt", "c", "C");
        commitFile("d.txt", "d", "D");
        commitFile("e.txt", "e", "E");

        Commit b = byMessage("B");
        assertThat(git.isAncestorOfHead(b.hash())).isTrue();
        assertThat(git.rebaseCandidates(b)).extracting(Commit::subject).containsExactly("B", "C", "D", "E");
        assertThat(git.isOnRemote(b.hash())).isFalse();

        // B reworded, D before C, E squashed into C
        var plan = steps("B", RebaseAction.REWORD, "D", RebaseAction.PICK, "C", RebaseAction.PICK, "E", RebaseAction.SQUASH);
        assertThat(git.rebaseInteractive(b, plan)).isEqualTo(MergeOutcome.DONE);

        assertThat(subjects()).containsExactly("C", "D", "Reworded B", "A");
        assertThat(git.log(0, 50, false).get(2).body()).isEqualTo("new body");
        for (String f : List.of("a.txt", "b.txt", "c.txt", "d.txt", "e.txt")) assertThat(dir.resolve(f)).exists();
        assertThat(git.operation()).isEqualTo(Operation.NONE);
        assertThat(dir.resolve(".git/gitgui-rebase")).doesNotExist();
    }

    @Test
    void customSquashMessageReplacesTheCombinedOne() throws Exception {
        commitFile("a.txt", "a", "A");
        commitFile("b.txt", "b", "B");
        commitFile("c.txt", "c", "C");
        commitFile("d.txt", "d", "D");

        List<RebaseStep> plan = List.of(
                new RebaseStep(RebaseAction.PICK, byMessage("B").hash(), "B", null),
                new RebaseStep(RebaseAction.SQUASH, byMessage("C").hash(), "C", "B+C\n\nhand written"),
                new RebaseStep(RebaseAction.FIXUP, byMessage("D").hash(), "D", null));
        assertThat(git.rebaseInteractive(byMessage("B"), plan)).isEqualTo(MergeOutcome.DONE);

        assertThat(subjects()).containsExactly("B+C", "A");
        assertThat(git.log(0, 50, false).get(0).body()).isEqualTo("hand written");
        for (String f : List.of("b.txt", "c.txt", "d.txt")) assertThat(dir.resolve(f)).exists();
        assertThat(dir.resolve(".git/gitgui-rebase")).doesNotExist();
    }

    @Test
    void dropRemovesChanges() throws Exception {
        commitFile("a.txt", "a", "A");
        commitFile("b.txt", "b", "B");
        commitFile("c.txt", "c", "C");
        assertThat(git.rebaseInteractive(byMessage("B"), steps("B", RebaseAction.DROP, "C", RebaseAction.PICK)))
                .isEqualTo(MergeOutcome.DONE);
        assertThat(subjects()).containsExactly("C", "A");
        assertThat(dir.resolve("b.txt")).doesNotExist();
    }

    @Test
    void worksFromTheRootCommit() throws Exception {
        commitFile("a.txt", "a", "A");
        commitFile("b.txt", "b", "B");
        assertThat(git.rebaseInteractive(byMessage("A"), steps("A", RebaseAction.REWORD, "B", RebaseAction.FIXUP)))
                .isEqualTo(MergeOutcome.DONE);
        assertThat(subjects()).containsExactly("Reworded A");
    }

    @Test
    void uncommittedChangesAreAutostashed() throws Exception {
        commitFile("a.txt", "a", "A");
        commitFile("b.txt", "b", "B");
        commitFile("c.txt", "c", "C");
        Files.writeString(dir.resolve("a.txt"), "dirty");
        assertThat(git.rebaseInteractive(byMessage("B"), steps("C", RebaseAction.PICK, "B", RebaseAction.PICK)))
                .isEqualTo(MergeOutcome.DONE);
        assertThat(Files.readString(dir.resolve("a.txt"))).isEqualTo("dirty");
    }

    @Test
    void conflictingReorderStopsAndCanBeAborted() throws Exception {
        commitFile("f.txt", "1\n", "A");
        commitFile("f.txt", "1\n2\n", "B");
        commitFile("f.txt", "1\n2\n3\n", "C");
        List<String> before = subjects();

        assertThat(git.rebaseInteractive(byMessage("B"), steps("C", RebaseAction.PICK, "B", RebaseAction.PICK)))
                .isEqualTo(MergeOutcome.CONFLICTS);
        assertThat(git.operation()).isEqualTo(Operation.REBASE);
        assertThat(git.status().hasConflicts()).isTrue();

        git.operationAbort(Operation.REBASE);
        assertThat(git.operation()).isEqualTo(Operation.NONE);
        assertThat(subjects()).isEqualTo(before);
        assertThat(dir.resolve(".git/gitgui-rebase")).doesNotExist();
    }

    @Test
    void notAnAncestorIsDetected() throws Exception {
        commitFile("a.txt", "a", "A");
        git.createBranch("side", "HEAD", true);
        commitFile("s.txt", "s", "S");
        git.checkoutBranch("main");
        assertThat(git.isAncestorOfHead(byMessageAll("S").hash())).isFalse();
    }

    Commit byMessageAll(String subject) {
        return git.log(0, 50, true).stream().filter(c -> c.subject().equals(subject)).findFirst().orElseThrow();
    }

    @Test
    void rebaseOntoMovesBranch() throws Exception {
        commitFile("a.txt", "a", "A");
        git.createBranch("feature", "HEAD", true);
        commitFile("f.txt", "f", "F");
        git.checkoutBranch("main");
        commitFile("m.txt", "m", "M");
        git.checkoutBranch("feature");
        assertThat(git.rebaseOnto("main")).isEqualTo(MergeOutcome.DONE);
        assertThat(subjects()).containsExactly("F", "M", "A");
    }
}
