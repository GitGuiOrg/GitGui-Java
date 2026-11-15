package com.github.gitguiorg.gitgui.util;

import java.util.List;

/**
 * Restores the saved window position without leaving the window on a monitor that no longer exists:
 * the coordinates are kept only when enough of the window is visible on some screen.
 */
public final class WindowPlacement {
    private WindowPlacement() {}

    /** A screen rectangle, as JavaFX reports it through {@code Screen.getVisualBounds()}. */
    public record Screen(double x, double y, double width, double height) {
        public double right() { return x + width; }
        public double bottom() { return y + height; }
    }

    /** Top-left corner for the window. */
    public record Position(double x, double y) {}

    /** How much of the window must fall inside a screen for the position to be kept. */
    private static final double MIN_VISIBLE_X = 100;
    private static final double MIN_VISIBLE_Y = 50;
    private static final double CASCADE = 40;

    /**
     * @param screens available screens, the fallback one first (normally the primary screen)
     * @return the given corner when the window is visible there, otherwise a cascaded corner of the fallback screen
     */
    public static Position visiblePosition(double x, double y, double w, double h, List<Screen> screens) {
        if (screens.isEmpty()) return new Position(x, y);
        if (Double.isFinite(x) && Double.isFinite(y)) {
            for (Screen s : screens) {
                double overlapX = Math.min(s.right(), x + w) - Math.max(s.x(), x);
                double overlapY = Math.min(s.bottom(), y + h) - Math.max(s.y(), y);
                if (overlapX >= Math.min(MIN_VISIBLE_X, w) && overlapY >= Math.min(MIN_VISIBLE_Y, h)) {
                    return new Position(x, y);
                }
            }
        }
        Screen first = screens.get(0);
        return new Position(first.x() + CASCADE, first.y() + CASCADE);
    }
}
