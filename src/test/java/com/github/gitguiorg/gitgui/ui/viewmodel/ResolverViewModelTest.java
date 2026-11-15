package com.github.gitguiorg.gitgui.ui.viewmodel;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.gitguiorg.gitgui.model.ConflictVersions;
import com.github.gitguiorg.gitgui.model.Operation;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ResolverViewModelTest {
    private static byte[] b(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    @Test
    void textConflictKeepsEolAndMergesChunks() {
        var l = ResolverViewModel.build(new ConflictVersions(b("a\r\nb\r\nc\r\n"), b("a\r\nX\r\nc\r\n"), b("a\r\nY\r\nc\r\n")));
        assertThat(l.text()).isTrue();
        assertThat(l.eol()).isEqualTo("\r\n");
        assertThat(l.trailingNewline()).isTrue();
        assertThat(l.chunks()).filteredOn(c -> c.conflict()).hasSize(1);
    }

    @Test
    void addAddHasNoBase() {
        var l = ResolverViewModel.build(new ConflictVersions(null, b("x\n"), b("y\n")));
        assertThat(l.text()).isTrue();
        assertThat(l.chunks()).filteredOn(c -> c.conflict()).hasSize(1);
    }

    @Test
    void rebaseLabelsNameTargetBranchAndAppliedCommit() {
        var l = ResolverViewModel.sideLabels(Operation.REBASE, "main", "abc1234 rework parser");
        assertThat(l.oursPanel()).contains("Ours").contains("main");
        assertThat(l.theirsPanel()).contains("Theirs").contains("abc1234 rework parser");
        assertThat(l.oursAction()).isEqualTo("Ours (rebased)");
        assertThat(l.theirsAction()).isEqualTo("Theirs (applied)");
    }

    @Test
    void rebaseLabelsFallBackWhenNamesAreMissing() {
        var l = ResolverViewModel.sideLabels(Operation.REBASE, null, " ");
        assertThat(l.oursPanel()).doesNotContain("null").contains("current branch");
        assertThat(l.theirsPanel()).doesNotContain("null").contains("incoming commit");
    }

    @Test
    void otherOperationsKeepTheUsualMeaning() {
        var normal = ResolverViewModel.sideLabels(Operation.NONE, "main", "abc1234 x");
        assertThat(ResolverViewModel.sideLabels(Operation.MERGE, null, null)).isEqualTo(normal);
        assertThat(ResolverViewModel.sideLabels(Operation.CHERRY_PICK, null, null)).isEqualTo(normal);
        assertThat(ResolverViewModel.sideLabels(Operation.REVERT, null, null)).isEqualTo(normal);
        assertThat(normal.oursPanel()).isEqualTo("Ours (current)");
        assertThat(normal.theirsPanel()).isEqualTo("Theirs (incoming)");
    }

    @Test
    void binaryOrDeletedSidesAreStructural() {
        var bin = ResolverViewModel.build(new ConflictVersions(b("a"), new byte[]{1, 0, 2}, b("c")));
        assertThat(bin.text()).isFalse();
        var del = ResolverViewModel.build(new ConflictVersions(b("a"), b("b"), null));
        assertThat(del.text()).isFalse();
        assertThat(del.hasOurs()).isTrue();
        assertThat(del.hasTheirs()).isFalse();
    }
}