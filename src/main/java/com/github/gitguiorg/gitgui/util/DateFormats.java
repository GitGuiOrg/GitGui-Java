package com.github.gitguiorg.gitgui.util;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Formats commit, blame and reflog dates as the user chose in the settings: absolute or relative,
 * in the local time zone or UTC. Views call {@link #configure(String, String)} once at startup and
 * again whenever the choice changes.
 */
public final class DateFormats {
    public static final String ABSOLUTE = "ABSOLUTE";
    public static final String RELATIVE = "RELATIVE";
    public static final String LOCAL = "LOCAL";
    public static final String UTC = "UTC";

    private static volatile boolean relative;
    private static volatile ZoneId zone = ZoneId.systemDefault();
    private static volatile DateTimeFormatter minutes = formatter("yyyy-MM-dd HH:mm");
    private static volatile DateTimeFormatter seconds = formatter("yyyy-MM-dd HH:mm:ss");
    private static volatile DateTimeFormatter day = formatter("yyyy-MM-dd");

    private DateFormats() {}

    public static void configure(String style, String timeZone) {
        relative = RELATIVE.equalsIgnoreCase(style);
        zone = UTC.equalsIgnoreCase(timeZone) ? ZoneOffset.UTC : ZoneId.systemDefault();
        minutes = formatter("yyyy-MM-dd HH:mm");
        seconds = formatter("yyyy-MM-dd HH:mm:ss");
        day = formatter("yyyy-MM-dd");
    }

    public static boolean isRelative() {
        return relative;
    }

    public static ZoneId zone() {
        return zone;
    }

    /** {@code yyyy-MM-dd HH:mm}, or a relative phrase such as {@code 3 h ago}. */
    public static String dateTime(Instant instant) {
        if (instant == null) return "";
        return relative ? relative(instant, Instant.now()) : minutes.format(instant);
    }

    /** Like {@link #dateTime(Instant)} with seconds in the absolute form. */
    public static String dateTimeSeconds(Instant instant) {
        if (instant == null) return "";
        return relative ? relative(instant, Instant.now()) : seconds.format(instant);
    }

    /** {@code yyyy-MM-dd}, or the same relative phrase used elsewhere. */
    public static String date(Instant instant) {
        if (instant == null) return "";
        return relative ? relative(instant, Instant.now()) : day.format(instant);
    }

    private static DateTimeFormatter formatter(String pattern) {
        return DateTimeFormatter.ofPattern(pattern, Locale.ROOT).withZone(zone);
    }

    /** Relative wording shared by the views; visible for tests. */
    static String relative(Instant at, Instant now) {
        long s = Math.max(0, Duration.between(at, now).getSeconds());
        if (s < 60) return "just now";
        long m = s / 60;
        if (m < 60) return m + " min ago";
        long h = m / 60;
        if (h < 24) return h + " h ago";
        long d = h / 24;
        if (d < 30) return d + (d == 1 ? " day ago" : " days ago");
        long mo = d / 30;
        if (mo < 12) return mo + (mo == 1 ? " month ago" : " months ago");
        // 360-364 days used to fall through with 12 months and 0 years; round the year count.
        long y = Math.max(1, Math.round(d / 365.0));
        return y + (y == 1 ? " year ago" : " years ago");
    }
}
