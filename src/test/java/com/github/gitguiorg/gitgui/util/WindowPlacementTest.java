package com.github.gitguiorg.gitgui.util;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.gitguiorg.gitgui.util.WindowPlacement.Position;
import com.github.gitguiorg.gitgui.util.WindowPlacement.Screen;
import java.util.List;
import org.junit.jupiter.api.Test;

class WindowPlacementTest {
    private static final Screen PRIMARY = new Screen(0, 0, 1920, 1040);
    private static final Screen SECOND = new Screen(1920, 0, 1280, 1024);

    @Test
    void keepsAPositionThatIsVisible() {
        assertThat(WindowPlacement.visiblePosition(100, 80, 1280, 800, List.of(PRIMARY, SECOND)))
                .isEqualTo(new Position(100, 80));
        // on the second monitor
        assertThat(WindowPlacement.visiblePosition(2000, 100, 1280, 800, List.of(PRIMARY, SECOND)))
                .isEqualTo(new Position(2000, 100));
    }

    @Test
    void movesTheWindowBackWhenItsMonitorIsGone() {
        Position p = WindowPlacement.visiblePosition(2600, 120, 1280, 800, List.of(PRIMARY));
        assertThat(p).isEqualTo(new Position(40, 40));
    }

    @Test
    void acceptsAWindowThatHangsOffTheEdgeButIsStillUsable() {
        assertThat(WindowPlacement.visiblePosition(-1000, 100, 1280, 800, List.of(PRIMARY)))
                .isEqualTo(new Position(-1000, 100));
    }

    @Test
    void fallsBackForMissingOrBrokenCoordinates() {
        assertThat(WindowPlacement.visiblePosition(Double.NaN, Double.NaN, 1280, 800, List.of(PRIMARY, SECOND)))
                .isEqualTo(new Position(40, 40));
        assertThat(WindowPlacement.visiblePosition(10, 10, 1280, 800, List.of()))
                .isEqualTo(new Position(10, 10));
    }
}
