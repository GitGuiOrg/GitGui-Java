package com.github.gitguiorg.gitgui.ui.view;

import com.github.gitguiorg.gitgui.git.GitClient;
import com.github.gitguiorg.gitgui.git.GitRunner.CommandRecord;
import com.github.gitguiorg.gitgui.ui.Fx;
import com.github.gitguiorg.gitgui.ui.viewmodel.RepoViewModel;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/** Modeless window with the git commands the app ran: exit code, duration and stderr, newest first. */
final class CommandLogDialog {
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final int MAX_ITEMS = 500;

    private CommandLogDialog() {}

    static void show(Window owner, RepoViewModel repo) {
        GitClient git = repo.session().git();
        ListView<CommandRecord> list = new ListView<>();
        TextArea details = new TextArea();
        details.setEditable(false);
        details.setPrefRowCount(8);

        list.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(CommandRecord r, boolean empty) {
                super.updateItem(r, empty);
                if (empty || r == null) {
                    setText(null);
                    setTooltip(null);
                    return;
                }
                setText(TIME.format(r.at()) + "   exit " + r.exitCode() + "   " + r.durationMs() + " ms   "
                        + r.commandLine());
                setTooltip(new javafx.scene.control.Tooltip(r.commandLine()));
            }
        });
        list.getSelectionModel().selectedItemProperty().addListener((o, a, r) -> details.setText(describe(r)));

        Runnable reload = () -> {
            List<CommandRecord> all = new ArrayList<>(git.commandHistory());
            Collections.reverse(all);   // newest first
            list.getItems().setAll(all);
            if (!all.isEmpty()) list.getSelectionModel().selectFirst();
        };
        reload.run();
        git.addCommandListener(r -> Fx.run(() -> {
            list.getItems().add(0, r);
            while (list.getItems().size() > MAX_ITEMS) list.getItems().remove(list.getItems().size() - 1);
        }));

        Button clear = new Button("Clear");
        clear.setOnAction(e -> {
            git.clearCommandHistory();
            list.getItems().clear();
            details.clear();
        });
        Label hint = new Label("Read-only: every command the app runs is listed here after it finishes.");
        hint.setOpacity(0.7);
        VBox bottom = new VBox(6, hint, new javafx.scene.layout.HBox(8, clear));
        bottom.setPadding(new Insets(6));

        SplitPane split = new SplitPane(list, details);
        split.setOrientation(javafx.geometry.Orientation.VERTICAL);
        split.setDividerPositions(0.55);
        BorderPane content = new BorderPane(split);
        content.setBottom(bottom);
        VBox.setVgrow(split, Priority.ALWAYS);

        Dialog<ButtonType> d = new Dialog<>();
        d.initOwner(owner);
        d.setTitle("Git commands");
        d.setResizable(true);
        d.getDialogPane().setContent(content);
        d.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        d.getDialogPane().setPrefSize(950, 550);
        d.show();
    }

    private static String describe(CommandRecord r) {
        if (r == null) return "";
        StringBuilder sb = new StringBuilder();
        sb.append(r.commandLine()).append("\n\n");
        sb.append("dir: ").append(r.dir()).append('\n');
        sb.append("exit: ").append(r.exitCode()).append('\n');
        sb.append("duration: ").append(r.durationMs()).append(" ms\n\n");
        String err = r.stderr() == null ? "" : r.stderr().strip();
        sb.append(err.isEmpty() ? "(no stderr)" : err);
        return sb.toString();
    }
}
