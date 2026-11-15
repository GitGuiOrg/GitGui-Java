package com.github.gitguiorg.gitgui;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.gitguiorg.gitgui.git.GitClient;
import com.github.gitguiorg.gitgui.git.GitCommand;
import com.github.gitguiorg.gitgui.git.GitRunner;
import com.github.gitguiorg.gitgui.git.PatchBuilder;
import com.github.gitguiorg.gitgui.model.DiffLine;
import com.github.gitguiorg.gitgui.model.FileDiff;
import com.github.gitguiorg.gitgui.model.MergeOutcome;
import com.github.gitguiorg.gitgui.model.Operation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PatchTest {
    @TempDir Path tmp;
    GitRunner runner;
    Path dir;
    GitClient git;

    @BeforeEach
    void setUp() {
        runner = new GitRunner(null);
        dir = tmp.resolve("repo");
        git = GitClient.init(dir, runner);
        config(dir, "user.name", "Test");
        config(dir, "user.email", "t@example.com");
        config(dir, "commit.gpgsign", "false");
        config(dir, "core.autocrlf", "false");
        runner.run(GitCommand.in(dir).args("checkout", "-q", "-b", "main"));
    }

    void config(Path d, String k, String v) { runner.run(GitCommand.in(d).args("config", k, v)); }

    void write(String rel, String content) throws IOException {
        Path p = dir.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    void commitAll(String msg) {
        git.stage(List.of("."));
        git.commit(msg, false);
    }

    /** A clone with its own identity, as a second checkout where patches are applied. */
    GitClient cloneInto(String name) {
        Path other = tmp.resolve(name);
        runner.run(GitCommand.in(tmp).args("-c", "core.autocrlf=false", "clone", "-q", dir.toString(), name));
        config(other, "user.name", "Test");
        config(other, "user.email", "t@example.com");
        config(other, "commit.gpgsign", "false");
        config(other, "core.autocrlf", "false");
        return GitClient.open(other, runner);
    }

    @Test
    void exportedCommitAppliesAsMailbox() throws Exception {
        write("f.txt", "one\n");
        commitAll("base");
        GitClient other = cloneInto("other");   // stands at the base commit
        write("f.txt", "two\n");
        commitAll("change");

        Path patch = tmp.resolve("change.patch");
        git.formatPatch("HEAD", patch);
        assertThat(Files.readString(patch)).startsWith("From ").contains("Subject: [PATCH] change");

        GitClient.PatchInfo info = other.patchInfo(patch);
        assertThat(info.mailbox()).isTrue();
        assertThat(info.applies()).isTrue();
        assertThat(info.stat()).contains("f.txt");

        assertThat(other.applyMailPatch(patch)).isEqualTo(MergeOutcome.DONE);
        assertThat(Files.readString(other.root().resolve("f.txt"))).isEqualTo("two\n");
        assertThat(other.log(0, 1, false).get(0).subject()).isEqualTo("change");
    }

    @Test
    void builtPatchAppliesToAnotherCheckout() throws Exception {
        write("f.txt", "a\nb\nc\nd\ne\nf\n");
        commitAll("base");
        GitClient other = cloneInto("builder");
        write("f.txt", "a\nB\nc\nd\nE\nf\n");

        FileDiff diff = git.diffWorkingTree("f.txt", 1).get(0);
        String patch = PatchBuilder.buildFile(diff, null).orElseThrow();
        assertThat(patch.split("diff --git", -1)).hasSize(2);   // one file header, two hunks

        Path file = tmp.resolve("changes.patch");
        Files.writeString(file, patch);
        assertThat(other.patchInfo(file).mailbox()).isFalse();
        assertThat(other.patchInfo(file).applies()).isTrue();
        assertThat(other.applyPatchFile(file)).isEqualTo(MergeOutcome.DONE);
        assertThat(Files.readString(other.root().resolve("f.txt"))).isEqualTo("a\nB\nc\nd\nE\nf\n");
    }

    @Test
    void stagingAndUnstagingASingleLineLeavesTheOtherChangeAlone() throws Exception {
        write("f.txt", "a\nb\nc\nd\ne\n");
        commitAll("base");
        write("f.txt", "a\nB\nc\nD\ne\n");

        FileDiff diff = git.diffWorkingTree("f.txt", 1).get(0);
        int[] target = addedLine(diff, "B");
        git.stageHunk(diff, target[0], java.util.Set.of(target[1]));

        FileDiff staged = git.diffStaged("f.txt", 1).get(0);
        List<String> stagedTexts = staged.hunks().stream().flatMap(h -> h.lines().stream())
                .filter(l -> l.type() == DiffLine.Type.ADDED).map(DiffLine::text).toList();
        assertThat(stagedTexts).containsExactly("B");
        // The other edit stays in the working tree, untouched.
        assertThat(Files.readString(dir.resolve("f.txt"))).isEqualTo("a\nB\nc\nD\ne\n");

        git.unstageHunk(staged, 0, java.util.Set.of(addedLine(staged, "B")[1]));
        assertThat(git.diffStaged("f.txt", 1)).isEmpty();
    }

    /** Hunk index and line index of the added line whose text is {@code text}, ignoring the carried CR. */
    private static int[] addedLine(FileDiff diff, String text) {
        for (int h = 0; h < diff.hunks().size(); h++) {
            List<DiffLine> lines = diff.hunks().get(h).lines();
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).type() == DiffLine.Type.ADDED
                        && com.github.gitguiorg.gitgui.util.Texts.stripCr(lines.get(i).text()).equals(text)) {
                    return new int[]{h, i};
                }
            }
        }
        throw new AssertionError("no added line " + text);
    }

    @Test
    void partialStagingWorksOnFilesStoredWithCrlf() throws Exception {
        write("f.txt", "a\r\nb\r\nc\r\n");
        commitAll("base");
        write("f.txt", "a\r\nB\r\nc\r\n");

        FileDiff diff = git.diffWorkingTree("f.txt", 1).get(0);
        int[] target = addedLine(diff, "B");
        git.stageHunk(diff, target[0], java.util.Set.of(target[1]));

        // staging only the addition leaves the removal aside, and the stored endings stay CRLF
        assertThat(runner.run(GitCommand.in(dir).args("show", ":f.txt")).out())
                .isEqualTo("a\r\nb\r\nB\r\nc\r\n");
        String stagedLine = git.diffStaged("f.txt", 1).get(0).hunks().get(0).lines().stream()
                .filter(l -> l.type() == DiffLine.Type.ADDED).findFirst().orElseThrow().text();
        assertThat(stagedLine).isEqualTo("B\r");
        assertThat(com.github.gitguiorg.gitgui.util.Texts.stripCr(stagedLine)).isEqualTo("B");
        assertThat(Files.readString(dir.resolve("f.txt"))).isEqualTo("a\r\nB\r\nc\r\n");
    }

    @Test
    void failedMailboxLeavesAnAbortableAmOperation() throws Exception {
        write("f.txt", "one\n");
        commitAll("base");
        GitClient other = cloneInto("conflict");
        write("f.txt", "from patch\n");
        commitAll("change");
        Path patch = tmp.resolve("conflict.patch");
        git.formatPatch("HEAD", patch);

        Files.writeString(other.root().resolve("f.txt"), "local\n");
        other.stage(List.of("f.txt"));
        other.commit("local change", false);

        GitClient.PatchInfo info = other.patchInfo(patch);
        assertThat(info.applies()).isFalse();
        assertThat(info.error()).isNotBlank();

        assertThat(other.applyMailPatch(patch)).isEqualTo(MergeOutcome.CONFLICTS);
        assertThat(other.operation()).isEqualTo(Operation.AM);
        other.operationAbort(Operation.AM);
        assertThat(other.operation()).isEqualTo(Operation.NONE);
        assertThat(Files.readString(other.root().resolve("f.txt"))).isEqualTo("local\n");
    }

    @Test
    void rangeExportWritesOnePatchPerCommit() throws Exception {
        write("a.txt", "a\n");
        commitAll("base");
        write("a.txt", "b\n");
        commitAll("change one");
        write("a.txt", "c\n");
        commitAll("change two");

        Path out = tmp.resolve("patches");
        Files.createDirectories(out);
        git.formatPatchRange("HEAD~2", "HEAD", false, out);

        try (var files = Files.list(out)) {
            List<String> names = files.map(p -> p.getFileName().toString()).toList();
            assertThat(names).hasSize(2);
            assertThat(names).anySatisfy(n -> assertThat(n).contains("change-one"));
            assertThat(names).anySatisfy(n -> assertThat(n).contains("change-two"));
        }
    }
}
