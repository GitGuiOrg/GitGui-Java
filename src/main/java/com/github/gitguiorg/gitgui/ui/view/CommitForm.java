package com.github.gitguiorg.gitgui.ui.view;

import com.github.gitguiorg.gitgui.ui.viewmodel.ChangesViewModel;
import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/** Message, options and author of the next commit. */
final class CommitForm extends VBox {
    private final ChangesViewModel vm;
    private final TextArea msg = new TextArea();

    CommitForm(ChangesViewModel vm) {
        this.vm = vm;
        msg.setPromptText("Commit message");
        msg.setWrapText(true);
        msg.textProperty().bindBidirectional(vm.messageProperty());
        CheckBox amend = new CheckBox("Amend");
        amend.selectedProperty().bindBidirectional(vm.amendProperty());
        amend.setTooltip(new Tooltip("Amend the last commit instead of creating a new one"));
        Button commit = new Button("Commit");
        commit.getStyleClass().add("accent");
        commit.setOnAction(e -> vm.commit());
        msg.setOnKeyPressed(e -> {
            if (e.isShortcutDown() && e.getCode() == KeyCode.ENTER && !commit.isDisabled()) {
                vm.commit();
                e.consume();
            }
        });
        Button stash = new Button("Stash…");
        stash.setOnAction(e -> stashDialog());
        stash.disableProperty().bind(Bindings.isEmpty(vm.unstaged()).and(Bindings.isEmpty(vm.staged())));
        CheckBox sign = new CheckBox("Sign");
        sign.selectedProperty().bindBidirectional(vm.signProperty());
        sign.setTooltip(new Tooltip("Sign the commit: git -S, using the GPG or SSH key from the git config"));
        Button advanced = new Button("Author…");
        advanced.setTooltip(new Tooltip("Override the author and date of this commit"));
        advanced.setOnAction(e -> authorDialog());
        Button skipHooks = new Button("Commit without hooks");
        skipHooks.setTooltip(new Tooltip("Retry with --no-verify, skipping the pre-commit and commit-msg hooks"));
        skipHooks.setOnAction(e -> vm.commitWithoutHooks());
        skipHooks.visibleProperty().bind(vm.hookFailedProperty());
        skipHooks.managedProperty().bind(vm.hookFailedProperty());

        Label counter = new Label();
        counter.setStyle("-fx-font-size: 11px; -fx-opacity: 0.8;");
        Runnable count = () -> {
            String first = vm.messageProperty().get().split("\n", 2)[0];
            int length = com.github.gitguiorg.gitgui.util.Texts.codePointLength(first);
            boolean longLine = length > 72;
            counter.setText("First line: " + length + " / 72" + (longLine ? " - too long" : ""));
            counter.setStyle("-fx-font-size: 11px;" + (longLine ? " -fx-text-fill: #d29922;" : " -fx-opacity: 0.8;"));
        };
        vm.messageProperty().addListener((o, a, b) -> count.run());
        count.run();

        Label override = new Label();
        Runnable showOverride = () -> {
            String a = vm.authorProperty().get().strip();
            String d = vm.dateProperty().get().strip();
            String text = (a.isEmpty() ? "" : "Author " + a) + (d.isEmpty() ? "" : (a.isEmpty() ? "" : " · ") + "date " + d);
            override.setText(text);
            override.setStyle("-fx-font-size: 11px; -fx-opacity: 0.8;");
            override.setVisible(!text.isEmpty());
            override.setManaged(!text.isEmpty());
        };
        vm.authorProperty().addListener((o, a, b) -> showOverride.run());
        vm.dateProperty().addListener((o, a, b) -> showOverride.run());
        showOverride.run();

        Label identity = new Label();
        identity.setOnMouseClicked(e -> identityDialog());
        identity.setTooltip(new Tooltip("Author git will use; click to change the repository-local user.name and user.email"));
        Runnable showIdentity = () -> {
            var id = vm.identityProperty().get();
            if (!id.known()) {
                identity.setText("Missing user.name / user.email - click to set them");
                identity.setStyle("-fx-font-size: 11px; -fx-text-fill: #d29922; -fx-cursor: hand;");
            } else {
                identity.setText("Author: " + id.label() + " (" + id.source() + ")");
                identity.setStyle("-fx-font-size: 11px; -fx-opacity: 0.8; -fx-cursor: hand;");
            }
        };
        vm.identityProperty().addListener((o, a, b) -> showIdentity.run());
        showIdentity.run();

        commit.disableProperty().bind(Bindings.createBooleanBinding(
                () -> !vm.identityProperty().get().known()
                        || (!vm.amendProperty().get() && (vm.messageProperty().get().isBlank() || vm.staged().isEmpty())),
                vm.messageProperty(), vm.amendProperty(), vm.staged(), vm.identityProperty()));

        setSpacing(4);
        setPadding(new Insets(4));
        javafx.scene.layout.FlowPane actions = new javafx.scene.layout.FlowPane(10, 3, amend, sign, commit, skipHooks, stash, advanced);
        actions.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        javafx.scene.layout.FlowPane info = new javafx.scene.layout.FlowPane(12, 0, counter, override, identity);
        getChildren().addAll(msg, actions, info);
        VBox.setVgrow(msg, Priority.ALWAYS);
    }

    /** Puts the caret in the message field, e.g. after choosing Commit from the command palette. */
    void focusMessage() {
        msg.requestFocus();
    }

    /** Edits the repository-local author, i.e. what git will record from the next commit on. */
    private void identityDialog() {
        var id = vm.identityProperty().get();
        Dialogs.askIdentity(id.name(), id.email()).ifPresent(v -> vm.setIdentity(v[0], v[1]));
    }

    private void authorDialog() {
        Dialogs.askAuthor(vm.authorProperty().get(), vm.dateProperty().get()).ifPresent(v -> {
            vm.authorProperty().set(v[0]);
            vm.dateProperty().set(v[1]);
        });
    }

    private void stashDialog() {
        TextField msg = new TextField();
        msg.setPromptText("Message (optional)");
        CheckBox untracked = new CheckBox("Include untracked files");
        untracked.setSelected(true);
        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle("Stash changes");
        d.getDialogPane().setContent(new VBox(8, msg, untracked));
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        d.showAndWait().filter(b -> b == ButtonType.OK).ifPresent(b -> vm.stash(msg.getText().strip(), untracked.isSelected()));
    }
}
