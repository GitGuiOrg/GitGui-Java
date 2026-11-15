package com.github.gitguiorg.gitgui.ui.view;

import com.github.gitguiorg.gitgui.git.ButClient;
import com.github.gitguiorg.gitgui.git.GitCommand;
import com.github.gitguiorg.gitgui.git.GitException;
import com.github.gitguiorg.gitgui.git.GitRunner;
import com.github.gitguiorg.gitgui.service.Settings;
import com.github.gitguiorg.gitgui.ui.viewmodel.MainViewModel;
import java.io.File;
import java.nio.file.Path;
import javafx.collections.FXCollections;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.stage.FileChooser;

/** Edits {@link Settings}: git executable, pull mode, auto-fetch, diff context and history page size. */
public final class SettingsDialog {
    private SettingsDialog() {}

    public static void show(MainViewModel vm) {
        Settings s = vm.settingsService().get();

        TextField git = new TextField(s.gitPath == null ? "" : s.gitPath);
        git.setPromptText("Empty = use git from PATH");
        HBox.setHgrow(git, Priority.ALWAYS);
        Button browse = new Button("Browse…");
        Button test = new Button("Test");
        Label testResult = new Label();
        testResult.setWrapText(true);
        try {
            testResult.setText(version(git.getText()));   // the version in use, before any test
            testResult.setOpacity(0.7);
        } catch (GitException ex) {
            testResult.setStyle("-fx-text-fill: #f85149;");
            testResult.setText(ex.getMessage());
        }

        TextField but = new TextField(s.butPath == null ? "" : s.butPath);
        but.setPromptText("Empty = find but.exe automatically");
        HBox.setHgrow(but, Priority.ALWAYS);
        Button butBrowse = new Button("Browse…");
        Button butTest = new Button("Test");
        Label butResult = new Label();
        butResult.setWrapText(true);
        try {
            butResult.setText("Detected: " + butVersion(but.getText()));
            butResult.setOpacity(0.7);
        } catch (RuntimeException ex) {
            butResult.setStyle("-fx-text-fill: #f85149;");
            butResult.setText("not found");
        }
        CheckBox useBut = new CheckBox("Use GitButler automatically in repositories that already use it");
        useBut.setSelected(s.gitButlerAuto);

        ComboBox<String> pull = new ComboBox<>(FXCollections.observableArrayList("Fast-forward only", "Merge", "Rebase"));
        pull.setValue(switch (s.pullMode == null ? "" : s.pullMode) {
            case "MERGE" -> "Merge";
            case "REBASE" -> "Rebase";
            default -> "Fast-forward only";
        });
        Spinner<Integer> autoFetch = spinner(0, 240, s.autoFetchMinutes, 1);
        Spinner<Integer> context = spinner(0, 50, vm.diffPreferences().contextLinesProperty().get(), 1);
        Spinner<Integer> page = spinner(50, 5000, s.logPageSize, 50);
        Spinner<Integer> maxLines = spinner(100, 200_000, vm.diffPreferences().maxLinesProperty().get(), 1000);
        CheckBox restore = new CheckBox("Reopen the tabs of the last session at startup");
        restore.setSelected(s.restoreSession);
        ComboBox<String> dateStyle = new ComboBox<>(FXCollections.observableArrayList("Absolute", "Relative"));
        dateStyle.setValue("RELATIVE".equalsIgnoreCase(s.dateStyle) ? "Relative" : "Absolute");
        dateStyle.setTooltip(new javafx.scene.control.Tooltip("Commit, blame and reflog dates: 2024-05-01 10:20 or 3 h ago"));
        ComboBox<String> dateZone = new ComboBox<>(FXCollections.observableArrayList("Local time", "UTC"));
        dateZone.setValue("UTC".equalsIgnoreCase(s.dateTimeZone) ? "UTC" : "Local time");

        GridPane g = new GridPane();
        g.setHgap(10);
        g.setVgap(10);
        g.setPadding(new Insets(10));
        ColumnConstraints c0 = new ColumnConstraints();
        ColumnConstraints c1 = new ColumnConstraints();
        c1.setHgrow(Priority.ALWAYS);
        g.getColumnConstraints().addAll(c0, c1);
        int r = 0;
        g.add(new Label("Git executable"), 0, r);
        g.add(new HBox(6, git, browse, test), 1, r++);
        g.add(testResult, 1, r++);
        g.add(new Label("GitButler CLI"), 0, r);
        g.add(new HBox(6, but, butBrowse, butTest), 1, r++);
        g.add(butResult, 1, r++);
        g.add(new Label("GitButler"), 0, r);
        g.add(useBut, 1, r++);
        g.add(new Label("Pull mode"), 0, r);
        g.add(pull, 1, r++);
        g.add(new Label("Auto-fetch (minutes)"), 0, r);
        g.add(new HBox(8, autoFetch, new Label("0 = off")), 1, r++);
        g.add(new Label("Diff context lines"), 0, r);
        g.add(context, 1, r++);
        g.add(new Label("History page size"), 0, r);
        g.add(page, 1, r++);
        g.add(new Label("Diff max lines"), 0, r);
        g.add(maxLines, 1, r++);
        g.add(new Label("Session"), 0, r);
        g.add(restore, 1, r++);
        g.add(new Label("Dates"), 0, r);
        g.add(new HBox(8, dateStyle, dateZone), 1, r++);
        Label note = new Label("A changed git path applies to repositories opened afterwards.");
        note.setOpacity(0.7);
        g.add(note, 0, r, 2, 1);
        g.setPrefWidth(620);

        browse.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("Select git executable");
            File f = fc.showOpenDialog(g.getScene().getWindow());
            if (f != null) git.setText(f.getAbsolutePath());
        });
        test.setOnAction(e -> {
            try {
                testResult.setStyle("-fx-text-fill: #3fb950;");
                testResult.setOpacity(1);
                testResult.setText("✓ " + version(git.getText()));
            } catch (GitException ex) {
                testResult.setStyle("-fx-text-fill: #f85149;");
                testResult.setText(ex.getMessage());
            }
        });
        butBrowse.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("Select but executable");
            File f = fc.showOpenDialog(g.getScene().getWindow());
            if (f != null) but.setText(f.getAbsolutePath());
        });
        butTest.setOnAction(e -> {
            try {
                butResult.setStyle("-fx-text-fill: #3fb950;");
                butResult.setOpacity(1);
                butResult.setText("✓ " + butVersion(but.getText()));
            } catch (RuntimeException ex) {
                butResult.setStyle("-fx-text-fill: #f85149;");
                butResult.setText(ex.getMessage());
            }
        });

        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle("Settings");
        d.setHeaderText("Settings");
        d.getDialogPane().setContent(g);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        Button ok = (Button) d.getDialogPane().lookupButton(ButtonType.OK);
        ok.addEventFilter(ActionEvent.ACTION, ev -> {
            for (Spinner<Integer> sp : java.util.List.of(autoFetch, context, page, maxLines)) sp.increment(0);   // commit typed text
            try {
                if (!git.getText().isBlank()) version(git.getText());
                if (!but.getText().isBlank() || useBut.isSelected()) butVersion(but.getText());
            } catch (GitException ex) {
                testResult.setStyle("-fx-text-fill: #f85149;");
                testResult.setText(ex.getMessage());
                ev.consume();
                return;
            } catch (RuntimeException ex) {
                butResult.setStyle("-fx-text-fill: #f85149;");
                butResult.setText(ex.getMessage());
                ev.consume();
                return;
            }
            s.gitPath = git.getText().isBlank() ? null : git.getText().strip();
            s.butPath = but.getText().isBlank() ? null : but.getText().strip();
            s.gitButlerAuto = useBut.isSelected();
            s.pullMode = switch (pull.getValue()) {
                case "Merge" -> "MERGE";
                case "Rebase" -> "REBASE";
                default -> "FF_ONLY";
            };
            s.autoFetchMinutes = autoFetch.getValue();
            vm.diffPreferences().contextLinesProperty().set(context.getValue());
            vm.diffPreferences().maxLinesProperty().set(maxLines.getValue());
            s.logPageSize = page.getValue();
            s.restoreSession = restore.isSelected();
            s.dateStyle = "Relative".equals(dateStyle.getValue()) ? "RELATIVE" : "ABSOLUTE";
            s.dateTimeZone = "UTC".equals(dateZone.getValue()) ? "UTC" : "LOCAL";
            vm.settingsService().save();
            com.github.gitguiorg.gitgui.util.DateFormats.configure(s.dateStyle, s.dateTimeZone);
            for (var repo : vm.repos()) {
                repo.log().reload();
                repo.refresh();
            }
            vm.applyGitPath();
        });
        d.showAndWait();
    }

    private static Spinner<Integer> spinner(int min, int max, int value, int step) {
        Spinner<Integer> sp = new Spinner<>(min, max, Math.max(min, Math.min(max, value)), step);
        sp.setEditable(true);
        sp.setPrefWidth(110);
        return sp;
    }

    private static String version(String path) {
        GitRunner runner = new GitRunner(path == null || path.isBlank() ? null : path.strip());
        return runner.run(GitCommand.in(Path.of(System.getProperty("user.home"))).args("--version")).out().strip();
    }

    private static String butVersion(String path) {
        return ButClient.versionOf(path == null || path.isBlank() ? null : path.strip());
    }
}
