package com.github.gitguiorg.gitgui.ui.view;

import javafx.scene.control.Label;

/** Coloured chip for a decoration from {@code %D}: "HEAD -> main", "origin/main", "tag: v1". */
final class RefBadge {
    private RefBadge() {}

    static Label create(String decoration) {
        String text = decoration;
        String bg;
        if (decoration.startsWith("HEAD -> ")) {
            text = decoration.substring(8);
            bg = "#8957e5";
        } else if (decoration.equals("HEAD")) {
            bg = "#8957e5";
        } else if (decoration.startsWith("tag: ")) {
            text = decoration.substring(5);
            bg = "#9e6a03";
        } else if (decoration.contains("/")) {
            bg = "#1f6feb";
        } else {
            bg = "#2ea043";
        }
        Label l = new Label(text);
        l.setStyle("-fx-background-color: " + bg + "; -fx-text-fill: white; -fx-background-radius: 3;"
                + " -fx-padding: 0 5 0 5; -fx-font-size: 11px;");
        return l;
    }
}
