package com.github.gitguiorg.gitgui.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.gitguiorg.gitgui.model.ConflictMarkers.Choice;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConflictMarkersTest {
    private static final List<String> MARKED = List.of(
            "a", ConflictMarkers.OURS, "x", ConflictMarkers.BASE, "b", ConflictMarkers.SEP,
            "y", ConflictMarkers.THEIRS, "c");

    @Test
    void completeBlockIsFoundAndResolvedWithoutMarkersLeft() {
        var blocks = ConflictMarkers.find(MARKED);
        assertThat(blocks).hasSize(1);
        assertThat(ConflictMarkers.hasMarkers(MARKED)).isTrue();
        List<String> resolved = ConflictMarkers.resolve(MARKED, blocks.get(0), Choice.OURS);
        assertThat(resolved).containsExactly("a", "x", "c");
        assertThat(ConflictMarkers.hasMarkers(resolved)).isFalse();
    }

    @Test
    void unclosedBlockIsReportedButCannotBeResolved() {
        List<String> broken = List.of("a", ConflictMarkers.OURS, "x", ConflictMarkers.SEP, "y", "c");
        assertThat(ConflictMarkers.find(broken)).isEmpty();
        assertThat(ConflictMarkers.hasMarkers(broken)).isTrue();

        List<String> onlyClosing = List.of("a", ConflictMarkers.THEIRS, "c");
        assertThat(ConflictMarkers.hasMarkers(onlyClosing)).isTrue();
    }

    @Test
    void ordinaryTextHasNoMarkers() {
        assertThat(ConflictMarkers.hasMarkers(List.of("a", "====", "b"))).isFalse();
        assertThat(ConflictMarkers.hasMarkers(List.of())).isFalse();
    }
}
