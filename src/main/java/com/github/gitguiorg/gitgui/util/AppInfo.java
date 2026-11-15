package com.github.gitguiorg.gitgui.util;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Name and version of the application, as filtered into the build resources. */
public final class AppInfo {
    public static final String NAME = "GitGui";
    public static final String VERSION = readVersion();

    private AppInfo() {}

    private static String readVersion() {
        try (InputStream in = AppInfo.class.getResourceAsStream("/com/github/gitguiorg/gitgui/version.properties")) {
            if (in == null) return "dev";
            Properties p = new Properties();
            p.load(in);
            String v = p.getProperty("version");
            return v == null || v.isBlank() ? "dev" : v.strip();
        } catch (IOException e) {
            return "dev";
        }
    }
}
