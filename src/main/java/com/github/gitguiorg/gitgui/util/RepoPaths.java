package com.github.gitguiorg.gitgui.util;

import java.io.File;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Repository paths used as identity (open tabs, recent list). The key is absolute and normalized; on Windows the
 * file system is case-insensitive, so the key is folded to lower case there and {@code C:\Repo} and {@code c:\repo}
 * are the same repository.
 */
public final class RepoPaths {
    private RepoPaths() {}

    /** Stable key of {@code path}: absolute, normalized and, on Windows only, case-folded. */
    public static String key(Path path) {
        String s = path.toAbsolutePath().normalize().toString();
        return File.separatorChar == '\\' ? s.toLowerCase(Locale.ROOT) : s;
    }

    /** True when both paths point at the same repository. */
    public static boolean same(Path a, Path b) {
        return key(a).equals(key(b));
    }

    /** Key of a path read from the settings file; null when the text is not a valid path. */
    public static String keyOrNull(String path) {
        if (path == null) return null;
        try {
            return key(Path.of(path));
        } catch (RuntimeException e) {
            return null;
        }
    }
}
