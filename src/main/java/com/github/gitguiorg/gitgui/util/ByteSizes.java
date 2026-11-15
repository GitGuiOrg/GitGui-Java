package com.github.gitguiorg.gitgui.util;

import java.util.Locale;

/** Human-readable file sizes for labels and tooltips, using 1024-based units. */
public final class ByteSizes {
    private static final String[] UNITS = {"B", "KB", "MB", "GB", "TB"};

    private ByteSizes() {}

    /**
     * {@code 512 B}, {@code 1.5 KB}, {@code 12.0 MB}; unknown sizes (negative) become empty.
     * The rounding never yields {@code 1024.0} of a lower unit: that value is promoted to the next unit.
     */
    public static String format(long bytes) {
        if (bytes < 0) return "";
        if (bytes < 1024) return bytes + " B";
        double value = bytes;
        int unit = 0;
        while (value >= 1023.95 && unit < UNITS.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(Locale.ROOT, "%.1f %s", value, UNITS[unit]);
    }
}
