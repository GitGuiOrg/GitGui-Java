package com.github.gitguiorg.gitgui.ui.viewmodel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.github.gitguiorg.gitgui.git.GitException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class HookFailureTest {
    private static GitException gitError(String stderr) {
        return new GitException("git commit failed", List.of("git", "commit"), 1, stderr);
    }

    @Test
    void hookMessageInStderrIsEnough() {
        AtomicBoolean asked = new AtomicBoolean();
        assertThat(ChangesViewModel.isHookFailure(gitError("pre-commit hook declined the commit"), () -> {
            asked.set(true);
            return false;
        })).isTrue();
        assertThat(asked).isFalse();   // no extra git command when the message already says it
    }

    @Test
    void aRepositoryWithHooksBlamesTheHookForAnyCommitError() {
        assertThat(ChangesViewModel.isHookFailure(gitError(""), () -> true)).isTrue();
        assertThat(ChangesViewModel.isHookFailure(new IllegalStateException("boom"), () -> true)).isTrue();
    }

    @Test
    void aBrokenHookCheckNeverEscapesNorSwallowsTheError() {
        assertThatCode(() -> assertThat(ChangesViewModel.isHookFailure(
                gitError("nothing to commit"), () -> {
                    throw gitError("git not found");
                })).isFalse()).doesNotThrowAnyException();
        assertThat(ChangesViewModel.isHookFailure(new IllegalStateException("boom"), () -> false)).isFalse();
    }
}
