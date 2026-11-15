package com.github.gitguiorg.gitgui.util;

import java.nio.file.Path;

public final class AppDirs {
    private AppDirs() {}

    /** %APPDATA%/GitGui on Windows, $XDG_CONFIG_HOME or ~/.config otherwise. */
    public static Path configDir() {
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) return Path.of(appData, "GitGui");
        String xdg = System.getenv("XDG_CONFIG_HOME");
        Path base = xdg != null && !xdg.isBlank() ? Path.of(xdg) : Path.of(System.getProperty("user.home"), ".config");
        return base.resolve("GitGui");
    }
}
