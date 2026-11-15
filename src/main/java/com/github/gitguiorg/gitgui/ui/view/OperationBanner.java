package com.github.gitguiorg.gitgui.ui.view;

import com.github.gitguiorg.gitgui.model.Operation;
import com.github.gitguiorg.gitgui.model.StatusEntry;
import com.github.gitguiorg.gitgui.ui.viewmodel.ChangesViewModel;
import com.github.gitguiorg.gitgui.util.Texts;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

/** Shown while a merge, cherry-pick, revert, rebase or bisect is in progress. */
final class OperationBanner extends HBox {
    OperationBanner(ChangesViewModel vm) {
        Label text = new Label();
        Button cont = new Button();
        cont.getStyleClass().add("accent");
        cont.setOnAction(e -> vm.operationContinue());
        Button bad = new Button("Bad");
        bad.setOnAction(e -> vm.bisectCurrent(false));
        Button skip = new Button("Skip");
        skip.setOnAction(e -> vm.bisectSkip());
        Button abort = new Button("Abort");
        abort.setOnAction(e -> {
            Operation op = vm.operationProperty().get();
            String message = op == Operation.BISECT
                    ? "Reset the bisect and go back to where it started?"
                    : "Abort the " + op.title.toLowerCase(java.util.Locale.ROOT) + " and discard its changes?";
            Alert a = new Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.OK, ButtonType.CANCEL);
            a.showAndWait().filter(b -> b == ButtonType.OK).ifPresent(b -> vm.operationAbort());
        });
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        getChildren().addAll(text, spacer, cont, bad, skip, abort);
        setAlignment(Pos.CENTER_LEFT);
        setPadding(new Insets(6, 10, 6, 10));
        setStyle("-fx-background-color: rgba(210, 153, 34, 0.18);");

        Runnable update = () -> {
            Operation op = vm.operationProperty().get();
            boolean on = op != Operation.NONE;
            setVisible(on);
            setManaged(on);
            if (!on) return;
            boolean bisect = op == Operation.BISECT;
            long n = vm.unstaged().stream().filter(StatusEntry::isConflicted).count();
            if (bisect) {
                var state = vm.bisectStateProperty().get();
                String left = state.candidates() > 0
                        ? "  |  " + Texts.count(state.candidates(), "candidate commit", "candidate commits")
                        + " left (~" + Texts.count(state.steps(), "step", "steps") + ")"
                        : "  |  mark a good and a bad commit from the log";
                text.setText("Bisect in progress" + left);
            } else {
                String msg = vm.operationMessage();
                text.setText(op.title + " in progress" + (msg != null && op == Operation.MERGE ? " - " + msg : "")
                        + (n > 0 ? "  |  " + Texts.count(n, "conflict", "conflicts") + " to resolve (double-click a file)"
                        : "  |  all conflicts resolved"));
            }
            bad.setVisible(bisect);
            bad.setManaged(bisect);
            skip.setVisible(bisect);
            skip.setManaged(bisect);
            cont.setText(bisect ? "Good" : op.continueLabel);
            cont.setDisable(n > 0);
            abort.setText(bisect ? "Reset" : "Abort");
        };
        vm.operationProperty().addListener((o, a, b) -> update.run());
        vm.bisectStateProperty().addListener((o, a, b) -> update.run());
        vm.unstaged().addListener((ListChangeListener<StatusEntry>) c -> update.run());
        update.run();
    }
}
