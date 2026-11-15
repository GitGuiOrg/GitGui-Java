package com.github.gitguiorg.gitgui;

/** Non-JavaFX entry point: avoids the "JavaFX runtime components are missing" error when started from the classpath. */
public final class Launcher {
    private Launcher() {}

    public static void main(String[] args) {
        GitGuiApp.main(args);
    }
}
