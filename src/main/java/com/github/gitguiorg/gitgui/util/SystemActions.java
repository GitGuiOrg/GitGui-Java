package com.github.gitguiorg.gitgui.util;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** Opening files, folders and terminals with the operating system, shared by the views. */
public final class SystemActions {
    private SystemActions() {}

    /** Opens a file or folder with the system default application. */
    public static void open(Path target) throws IOException {
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            throw new IOException("The system cannot open files or folders with a desktop application.");
        }
        Desktop.getDesktop().open(target.toFile());
    }

    /** Opens a terminal window in {@code dir}. */
    public static void openTerminal(Path dir) throws IOException {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        List<String> command = windows
                ? List.of("cmd", "/c", "start", "", "cmd", "/k", "cd", "/d", dir.toString())
                : List.of("x-terminal-emulator", "-e", "bash", "-lc", "cd " + dir + " && exec bash");
        new ProcessBuilder(command).start();
    }
}
