package com.github.gitguiorg.gitgui.ui.view;

import com.github.gitguiorg.gitgui.git.PromptInfo;
import com.github.gitguiorg.gitgui.ui.Fx;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

/**
 * Asks for the user name / password / passphrase git (or ssh) is waiting for. Nothing is stored by the app;
 * the host key prompt becomes a Yes/No question and the password dialog says when no credential helper exists.
 */
public final class CredentialDialog {
    /** True when git has a credential helper configured (injected at startup, runs git). */
    private static volatile BooleanSupplier helperConfigured;
    /** Enables Git Credential Manager globally; may be null when not wired. */
    private static volatile Runnable enableManager;
    private static volatile String lastUserName;

    private CredentialDialog() {}

    public static void setCredentialHelperSupport(BooleanSupplier configured, Runnable enable) {
        helperConfigured = configured;
        enableManager = enable;
    }

    /** Callable from any background thread; blocks until the dialog is closed. */
    public static Optional<String> ask(String prompt, boolean secret) {
        PromptInfo info = PromptInfo.parse(prompt);
        CompletableFuture<Optional<String>> result = new CompletableFuture<>();
        Fx.run(() -> {
            try {
                result.complete(show(prompt, info, secret));
            } catch (RuntimeException e) {
                result.complete(Optional.empty());
            }
        });
        Optional<String> answer;
        try {
            answer = result.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (java.util.concurrent.ExecutionException e) {
            return Optional.empty();
        }
        if (answer.isPresent() && info.kind() == PromptInfo.Kind.USERNAME) lastUserName = answer.get();
        return answer;
    }

    private static Optional<String> show(String prompt, PromptInfo info, boolean secret) {
        if (info.hostKey()) return confirmHostKey(info);

        ButtonType ok = new ButtonType("OK", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        Dialog<String> d = new Dialog<>();
        d.setTitle("Authentication");
        d.setHeaderText(info.title());
        d.getDialogPane().getButtonTypes().addAll(ok, cancel);

        Label label = new Label(labelFor(prompt, info));
        label.setWrapText(true);
        label.setMaxWidth(460);
        TextField field = secret ? new PasswordField() : new TextField();
        field.setPrefColumnCount(32);
        Label hint = new Label(hintFor(info, secret));
        hint.setOpacity(0.65);
        hint.setWrapText(true);
        hint.setMaxWidth(460);
        VBox box = new VBox(8, label, field, hint);
        if (secret && needsHelperHint()) box.getChildren().add(enableManagerButton(hint));
        box.setPadding(new Insets(8));
        d.getDialogPane().setContent(box);
        d.setResultConverter(bt -> bt == ok ? field.getText() : null);
        javafx.application.Platform.runLater(field::requestFocus);
        return d.showAndWait();
    }

    private static Optional<String> confirmHostKey(PromptInfo info) {
        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle("SSH host key");
        d.setHeaderText(info.host() == null ? "First connection to an unknown host" : "First connection to " + info.host());
        d.getDialogPane().getButtonTypes().addAll(ButtonType.YES, ButtonType.NO);
        Label body = new Label((info.fingerprint().map(f -> "Key fingerprint:\n" + f + "\n\n").orElse(""))
                + "Trust this host and continue connecting?");
        body.setWrapText(true);
        body.setMaxWidth(460);
        VBox box = new VBox(8, body);
        box.setPadding(new Insets(8));
        d.getDialogPane().setContent(box);
        return d.showAndWait().filter(bt -> bt == ButtonType.YES).map(bt -> "yes");
    }

    private static String labelFor(String prompt, PromptInfo info) {
        return switch (info.kind()) {
            case USERNAME -> "User name";
            case PASSWORD -> "Password";
            case PASSPHRASE -> info.detail() == null ? "Passphrase" : "Passphrase for " + info.detail();
            default -> {
                String text = prompt.strip();
                yield text.endsWith(":") ? text.substring(0, text.length() - 1) : text;
            }
        };
    }

    private static String hintFor(PromptInfo info, boolean secret) {
        if (!secret) {
            return info.kind() == PromptInfo.Kind.USERNAME && lastUserName != null
                    ? "Last user name: " + lastUserName : "";
        }
        if (needsHelperHint()) {
            return "git has no credential helper configured, so this password will not be remembered.";
        }
        return "Whether this is remembered is decided by git's credential helper (e.g. Git Credential Manager).";
    }

    private static boolean needsHelperHint() {
        BooleanSupplier c = helperConfigured;
        if (c == null) return false;
        try {
            return !c.getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static Button enableManagerButton(Label hint) {
        Button b = new Button("Use Git Credential Manager");
        b.setTooltip(new javafx.scene.control.Tooltip("Runs: git config --global credential.helper manager"));
        b.setOnAction(e -> {
            Runnable enable = enableManager;
            if (enable == null) return;
            b.setDisable(true);
            Thread.startVirtualThread(() -> {
                try {
                    enable.run();
                    Fx.run(() -> hint.setText("Git Credential Manager enabled: the next password can be remembered."));
                } catch (RuntimeException ex) {
                    Fx.run(() -> Fx.showError("Cannot enable Git Credential Manager", ex));
                } finally {
                    Fx.run(() -> b.setDisable(false));
                }
            });
        });
        return b;
    }
}
