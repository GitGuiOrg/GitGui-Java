package com.github.gitguiorg.gitgui.util;

/** Remote URLs as git prints them: folder names and credential-free display forms. */
public final class RemoteUrls {
    private RemoteUrls() {}

    /**
     * Folder name git picks for a URL or path: {@code https://h/o/r.git} -> {@code r},
     * {@code git@h:o/r.git} -> {@code r}, {@code C:\repos\r\} -> {@code r}. Blank input gives an empty name.
     */
    public static String repoName(String url) {
        String s = url == null ? "" : url.strip();
        while (s.endsWith("/") || s.endsWith("\\")) s = s.substring(0, s.length() - 1);
        if (s.endsWith(".git")) s = s.substring(0, s.length() - 4);
        int i = Math.max(Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\')), s.lastIndexOf(':'));
        return s.substring(i + 1);
    }

    /**
     * The URL with a password or token replaced by {@code ***}, for labels and tooltips: the token in
     * {@code https://token@host/repo.git} must not be shown. The user part of ssh URLs (which is not a secret),
     * scp-like URLs such as {@code git@host:repo.git}, and plain paths come back unchanged apart from the
     * surrounding blanks.
     */
    public static String display(String url) {
        if (url == null) return "";
        String s = url.strip();
        int scheme = s.indexOf("://");
        if (scheme < 0) return s;
        String protocol = s.substring(0, scheme).toLowerCase(java.util.Locale.ROOT);
        if (protocol.equals("ssh") || protocol.equals("git")) return s;
        int authority = scheme + 3;
        int at = s.indexOf('@', authority);
        int slash = s.indexOf('/', authority);
        if (at < 0 || (slash >= 0 && at > slash)) return s;
        return s.substring(0, authority) + "***@" + s.substring(at + 1);
    }
}
