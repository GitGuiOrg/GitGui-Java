package com.github.gitguiorg.gitgui.ui;

import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.control.TextArea;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import com.github.gitguiorg.gitgui.git.GitException;
import com.github.gitguiorg.gitgui.util.Texts;

public final class Fx {
    private Fx() {}

    /** Runs on the FX thread (immediately if already on it). */
    public static void run(Runnable r) {
        if (Platform.isFxApplicationThread()) r.run(); else Platform.runLater(r);
    }

    public static Throwable unwrap(Throwable t) {
        while ((t instanceof java.util.concurrent.CompletionException || t instanceof java.util.concurrent.ExecutionException)
                && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    public static void showError(String title, Throwable error) {
        Throwable t = unwrap(error);
        run(() -> {
            Alert a = new Alert(Alert.AlertType.ERROR, null, ButtonType.OK);
            a.setTitle(title);
            a.setHeaderText(title);
            String msg = t.getMessage() == null ? t.toString() : t.getMessage();
            a.setContentText(Texts.ellipsis(msg, 200));
            if (t instanceof GitException g && !g.command().isEmpty()) {
                TextArea details = new TextArea(String.join(" ", g.command()) + "\n\nexit " + g.exitCode() + "\n\n" + g.stderr());
                details.setEditable(false);
                details.setPrefRowCount(10);
                VBox.setVgrow(details, Priority.ALWAYS);
                a.getDialogPane().setExpandableContent(details);
                if (g.stderr() != null && g.stderr().toLowerCase(java.util.Locale.ROOT).contains("hook")) {
                    a.setContentText("A git hook stopped the operation; its full output is shown below.");
                    a.getDialogPane().setExpanded(true);
                }
                ButtonType copy = new ButtonType("Copy command", ButtonBar.ButtonData.OTHER);
                a.getButtonTypes().add(copy);
                if (a.getDialogPane().lookupButton(copy) instanceof javafx.scene.control.Button b) {
                    b.addEventFilter(ActionEvent.ACTION, ev -> {
                        ClipboardContent cc = new ClipboardContent();
                        cc.putString(String.join(" ", g.command()));
                        Clipboard.getSystemClipboard().setContent(cc);
                        ev.consume();   // keep the dialog open so the command can be read
                    });
                }
            }
            a.show();
        });
    }
}
