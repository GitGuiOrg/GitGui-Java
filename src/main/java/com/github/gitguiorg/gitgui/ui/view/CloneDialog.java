package com.github.gitguiorg.gitgui.ui.view;

import com.github.gitguiorg.gitgui.git.CancelToken;
import com.github.gitguiorg.gitgui.git.GitCancelledException;
import com.github.gitguiorg.gitgui.ui.Fx;
import com.github.gitguiorg.gitgui.ui.viewmodel.MainViewModel;
import com.github.gitguiorg.gitgui.util.RemoteUrls;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.stage.DirectoryChooser;

/** Clone a repository: URL, destination folder and name, with progress and cancel. */
public final class CloneDialog {
    private CloneDialog() {}

    public static void show(MainViewModel vm) {
        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle("Clone repository");
        d.setHeaderText("Clone a remote repository");
        ButtonType cloneType = new ButtonType("Clone", ButtonBar.ButtonData.OK_DONE);
        d.getDialogPane().getButtonTypes().addAll(cloneType, ButtonType.CANCEL);

        TextField url = new TextField();
        url.setPromptText("https://host/owner/repo.git  or  git@host:owner/repo.git  or a local path");
        TextField parent = new TextField(vm.defaultCloneDir());
        Button browse = new Button("Browse…");
        TextField name = new TextField();
        Label target = new Label();
        target.setOpacity(0.7);
        Label error = new Label();
        error.setStyle("-fx-text-fill: #f85149;");
        error.setWrapText(true);
        Label phase = new Label();
        ProgressBar bar = new ProgressBar(-1);
        bar.setMaxWidth(Double.MAX_VALUE);

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.setPadding(new Insets(8));
        ColumnConstraints c0 = new ColumnConstraints();
        ColumnConstraints c1 = new ColumnConstraints();
        c1.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(c0, c1, new ColumnConstraints());
        grid.add(new Label("Repository URL"), 0, 0);
        grid.add(url, 1, 0, 2, 1);
        grid.add(new Label("Clone into"), 0, 1);
        grid.add(parent, 1, 1);
        grid.add(browse, 2, 1);
        grid.add(new Label("Folder name"), 0, 2);
        grid.add(name, 1, 2, 2, 1);
        grid.add(target, 1, 3, 2, 1);
        HBox progressRow = new HBox(8, bar, phase);
        HBox.setHgrow(bar, Priority.ALWAYS);
        progressRow.setVisible(false);
        progressRow.setManaged(false);
        grid.add(progressRow, 0, 4, 3, 1);
        grid.add(error, 0, 5, 3, 1);
        grid.setPrefWidth(620);
        d.getDialogPane().setContent(grid);

        boolean[] nameEdited = {false};
        boolean[] busy = {false};
        CancelToken[] token = {null};

        Runnable updateTarget = () -> {
            try {
                target.setText(name.getText().isBlank() ? "" : "Will create: " + Path.of(parent.getText().strip(), name.getText().strip()));
            } catch (InvalidPathException e) {
                target.setText("");
            }
        };
        name.setOnKeyTyped(e -> nameEdited[0] = true);
        url.textProperty().addListener((o, a, b) -> {
            if (!nameEdited[0]) name.setText(RemoteUrls.repoName(b));
        });
        name.textProperty().addListener((o, a, b) -> updateTarget.run());
        parent.textProperty().addListener((o, a, b) -> updateTarget.run());
        browse.setOnAction(e -> {
            DirectoryChooser dc = new DirectoryChooser();
            dc.setTitle("Clone into folder");
            File init = new File(parent.getText().strip());
            if (init.isDirectory()) dc.setInitialDirectory(init);
            File f = dc.showDialog(d.getDialogPane().getScene().getWindow());
            if (f != null) parent.setText(f.getAbsolutePath());
        });

        Button cloneBtn = (Button) d.getDialogPane().lookupButton(cloneType);
        Button cancelBtn = (Button) d.getDialogPane().lookupButton(ButtonType.CANCEL);
        // Not a binding: the button is also disabled while a clone runs, and a bound JavaFX property
        // cannot be set with setDisable(), which used to throw before the clone even started.
        Runnable updateCloneButton = () -> cloneBtn.setDisable(busy[0] || url.getText().isBlank());
        url.textProperty().addListener((o, a, b) -> updateCloneButton.run());
        updateCloneButton.run();

        cloneBtn.addEventFilter(ActionEvent.ACTION, ev -> {
            ev.consume();
            if (busy[0]) return;
            String u = url.getText().strip();
            String n = name.getText().strip();
            Path p;
            try {
                p = Path.of(parent.getText().strip());
            } catch (InvalidPathException ex) {
                error.setText("Invalid destination folder.");
                return;
            }
            if (u.isEmpty() || n.isEmpty()) {
                error.setText("Enter the repository URL and a folder name.");
                return;
            }
            if (Files.exists(p.resolve(n))) {
                error.setText("“" + p.resolve(n) + "” already exists.");
                return;
            }
            error.setText("");
            busy[0] = true;
            token[0] = new CancelToken();
            for (var node : new javafx.scene.Node[]{url, parent, name, browse, cloneBtn}) node.setDisable(true);
            progressRow.setVisible(true);
            progressRow.setManaged(true);
            phase.setText("Cloning…");
            bar.setProgress(-1);
            d.getDialogPane().getScene().getWindow().sizeToScene();

            vm.cloneRepository(u, p, n,
                    prog -> Fx.run(() -> {
                        phase.setText(prog.phase() + " " + prog.percent() + "%");
                        bar.setProgress(prog.percent() / 100.0);
                    }),
                    token[0],
                    err -> Fx.run(() -> {
                        busy[0] = false;
                        for (var node : new javafx.scene.Node[]{url, parent, name, browse}) node.setDisable(false);
                        updateCloneButton.run();
                        progressRow.setVisible(false);
                        progressRow.setManaged(false);
                        Throwable t = Fx.unwrap(err);
                        error.setText(t instanceof GitCancelledException ? "Cancelled."
                                : t instanceof com.github.gitguiorg.gitgui.git.GitAuthCancelledException
                                ? "Authentication cancelled: the clone was not completed."
                                : (t.getMessage() == null ? t.toString() : t.getMessage().strip()));
                    }),
                    () -> Fx.run(() -> { busy[0] = false; d.setResult(cloneType); d.close(); }));
        });

        // Cancel / window close stop a running clone instead of just hiding the dialog.
        cancelBtn.addEventFilter(ActionEvent.ACTION, ev -> {
            if (busy[0]) {
                ev.consume();
                phase.setText("Cancelling…");
                token[0].cancel();
            }
        });
        d.setOnCloseRequest(ev -> {
            if (busy[0]) {
                ev.consume();
                phase.setText("Cancelling…");
                token[0].cancel();
            }
        });
        d.show();
    }
}
