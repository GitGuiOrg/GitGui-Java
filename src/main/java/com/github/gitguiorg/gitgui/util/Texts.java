package com.github.gitguiorg.gitgui.util;

/** Small text helpers for labels, dialogs and error banners. */
public final class Texts {
    private Texts() {}

    /** {@code text} cut to at most {@code max} code points, with an ellipsis when it was longer. */
    public static String ellipsis(String text, int max) {
        if (text == null || max <= 0) return "";
        if (text.codePointCount(0, text.length()) <= max) return text;
        return text.substring(0, text.offsetByCodePoints(0, max)) + "…";
    }

    /** First line of {@code text} without surrounding blanks, cut with {@link #ellipsis}; blank text is empty. */
    public static String firstLine(String text, int max) {
        if (text == null || text.isBlank()) return "";
        return ellipsis(text.strip().lines().findFirst().orElse(""), max);
    }

    /** {@code text} without a trailing carriage return, as CRLF diff and blame lines carry one. */
    public static String stripCr(String text) {
        if (text == null) return "";
        return text.endsWith("\r") ? text.substring(0, text.length() - 1) : text;
    }

    /** Number of visible characters (code points) in {@code text}; null is 0. */
    public static int codePointLength(String text) {
        return text == null ? 0 : text.codePointCount(0, text.length());
    }

    /** {@code n} followed by {@code singular} or {@code plural}, e.g. {@code 1 file} and {@code 2 files}. */
    public static String count(long n, String singular, String plural) {
        return n + " " + (n == 1 ? singular : plural);
    }
}
