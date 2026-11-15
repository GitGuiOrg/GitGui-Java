package com.github.gitguiorg.gitgui.ui.view;

import com.github.gitguiorg.gitgui.model.Commit;
import com.github.gitguiorg.gitgui.model.RebaseAction;
import com.github.gitguiorg.gitgui.model.RebasePlan;
import com.github.gitguiorg.gitgui.model.RebaseStep;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/** Edit the todo list of an interactive rebase: per-commit action, reword text and order (drag or buttons). */
public final class InteractiveRebaseDialog {
    private static final class Row {
        final Commit commit;
        final ObjectProperty<RebaseAction> action = new SimpleObjectProperty<>(RebaseAction.PICK);
        String newMessage;

        Row(Commit commit) { this.commit = commit; }
    }

    private InteractiveRebaseDialog() {}

    public static Optional<List<RebaseStep>> show(RebasePlan plan) {
        ObservableList<Row> rows = FXCollections.observableArrayList();
        for (Commit c : plan.commits()) rows.add(new Row(c));

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Interactive rebase");
        dialog.setHeaderText("Rebase " + plan.commits().size() + " commit(s) starting at " + plan.from().shortHash()
                + " — oldest first, applied top to bottom");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.setResizable(true);

        ListView<Row> list = new ListView<>(rows);
        list.setFixedCellSize(32);
        list.setPrefSize(760, 360);

        Label problem = new Label();
        problem.setStyle("-fx-text-fill: #f85149;");
        Label remoteWarning = new Label("Some of these commits are already on a remote. Rewriting them requires a force push.");
        remoteWarning.setStyle("-fx-text-fill: #d29922;");
        remoteWarning.setVisible(plan.onRemote());
        remoteWarning.setManaged(plan.onRemote());

        Runnable validate = () -> {
            Row first = rows.stream().filter(r -> r.action.get() != RebaseAction.DROP).findFirst().orElse(null);
            String msg = "";
            if (first == null) msg = "Everything is dropped: nothing to rebase.";
            else if (first.action.get() == RebaseAction.SQUASH || first.action.get() == RebaseAction.FIXUP) {
                msg = "The first kept commit cannot be squash or fixup.";
            }
            problem.setText(msg);
            dialog.getDialogPane().lookupButton(ButtonType.OK).setDisable(!msg.isEmpty());
        };
        list.setCellFactory(lv -> new RowCell(rows, list, validate));
        rows.addListener((javafx.collections.ListChangeListener<Row>) c -> validate.run());

        Button up = new Button("Move up");
        up.setOnAction(e -> move(rows, list, -1));
        Button down = new Button("Move down");
        down.setOnAction(e -> move(rows, list, 1));
        up.disableProperty().bind(list.getSelectionModel().selectedIndexProperty().lessThanOrEqualTo(0));
        down.disableProperty().bind(list.getSelectionModel().selectedIndexProperty().lessThan(0)
                .or(list.getSelectionModel().selectedIndexProperty().greaterThanOrEqualTo(rows.size() - 1)));

        VBox box = new VBox(8, list, new HBox(8, up, down), remoteWarning, problem);
        box.setPadding(new Insets(4));
        VBox.setVgrow(list, Priority.ALWAYS);
        dialog.getDialogPane().setContent(box);
        validate.run();

        Optional<ButtonType> res = dialog.showAndWait();
        if (res.isEmpty() || res.get() != ButtonType.OK) return Optional.empty();
        return Optional.of(toSteps(rows));
    }

    /** Steps in list order; a custom squash message travels to the last squash of its group, where the amend goes. */
    private static List<RebaseStep> toSteps(List<Row> rows) {
        List<RebaseStep> steps = new ArrayList<>();
        for (Row r : rows) {
            steps.add(new RebaseStep(r.action.get(), r.commit.hash(), r.commit.subject(),
                    r.action.get() == RebaseAction.REWORD ? r.newMessage : null));
        }
        int lastSquash = -1;
        String pending = null;
        for (int i = 0; i < rows.size(); i++) {
            RebaseAction a = rows.get(i).action.get();
            if (a == RebaseAction.DROP) continue;   // dropped commits do not break a squash group
            if (a == RebaseAction.SQUASH) {
                if (rows.get(i).newMessage != null) pending = rows.get(i).newMessage;
                lastSquash = i;
                continue;
            }
            attachSquashMessage(steps, lastSquash, pending);
            lastSquash = -1;
            pending = null;
        }
        attachSquashMessage(steps, lastSquash, pending);
        return steps;
    }

    private static void attachSquashMessage(List<RebaseStep> steps, int lastSquash, String message) {
        if (lastSquash < 0 || message == null) return;
        RebaseStep s = steps.get(lastSquash);
        steps.set(lastSquash, new RebaseStep(RebaseAction.SQUASH, s.hash(), s.subject(), message));
    }

    /** True when row {@code i} is the last squash of its group: the place where the group's message is set. */
    private static boolean lastSquashOfGroup(List<Row> rows, int i) {
        for (int j = i + 1; j < rows.size(); j++) {
            RebaseAction a = rows.get(j).action.get();
            if (a != RebaseAction.DROP) return a != RebaseAction.SQUASH;
        }
        return true;
    }

    /**
     * Git's default message for the squash group ending at row {@code end}: the base commit's message followed by
     * the messages of the commits squashed into it (fixups discard theirs).
     */
    private static String defaultSquashMessage(List<Row> rows, int end) {
        int start = end;
        while (start > 0) {
            RebaseAction prev = rows.get(start - 1).action.get();
            if (prev == RebaseAction.PICK || prev == RebaseAction.REWORD) break;
            start--;
        }
        StringBuilder sb = new StringBuilder(fullMessage(rows.get(Math.max(0, start - 1)).commit));
        for (int i = start; i <= end && i < rows.size(); i++) {
            Row r = rows.get(i);
            if (r.action.get() == RebaseAction.SQUASH) sb.append("\n\n").append(fullMessage(r.commit));
        }
        return sb.toString();
    }

    private static void move(ObservableList<Row> rows, ListView<Row> list, int delta) {
        int i = list.getSelectionModel().getSelectedIndex();
        int j = i + delta;
        if (i < 0 || j < 0 || j >= rows.size()) return;
        Row r = rows.remove(i);
        rows.add(j, r);
        list.getSelectionModel().select(j);
        list.refresh();   // which squash ends a group may have changed
    }

    private static String fullMessage(Commit c) {
        return c.body().isBlank() ? c.subject() : c.subject() + "\n\n" + c.body();
    }

    private static final class RowCell extends ListCell<Row> {
        private final ObservableList<Row> rows;
        private final ListView<Row> list;
        private final Runnable validate;
        private final Label handle = new Label("⋮⋮");
        private final ComboBox<RebaseAction> combo = new ComboBox<>(FXCollections.observableArrayList(RebaseAction.values()));
        private final Label hash = new Label();
        private final Label subject = new Label();
        private final Label edited = new Label("✎ message edited");
        private final HBox box;
        private boolean updating;

        RowCell(ObservableList<Row> rows, ListView<Row> list, Runnable validate) {
            this.rows = rows;
            this.list = list;
            this.validate = validate;
            combo.setPrefWidth(95);
            hash.setMinWidth(60);
            hash.setStyle("-fx-font-family: 'Consolas', monospace; -fx-text-fill: #79c0ff;");
            subject.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(subject, Priority.ALWAYS);
            edited.setStyle("-fx-text-fill: #d29922; -fx-font-size: 11px;");
            handle.setStyle("-fx-cursor: move; -fx-text-fill: #8b949e;");
            box = new HBox(8, handle, combo, hash, subject, edited);
            box.setAlignment(Pos.CENTER_LEFT);

            combo.setOnAction(e -> {
                Row r = getItem();
                if (updating || r == null) return;
                RebaseAction a = combo.getValue();
                r.action.set(a);
                if (a == RebaseAction.REWORD) editMessage(r);
                list.refresh();
                validate.run();
            });
            edited.setOnMouseClicked(e -> { if (getItem() != null) { editMessage(getItem()); list.refresh(); } });

            handle.setOnDragDetected(e -> {
                if (getItem() == null) return;
                Dragboard db = handle.startDragAndDrop(TransferMode.MOVE);
                ClipboardContent cc = new ClipboardContent();
                cc.putString(String.valueOf(getIndex()));
                db.setContent(cc);
                e.consume();
            });
            setOnDragOver(e -> {
                if (e.getDragboard().hasString()) e.acceptTransferModes(TransferMode.MOVE);
                e.consume();
            });
            setOnDragDropped(e -> {
                Dragboard db = e.getDragboard();
                boolean ok = false;
                if (db.hasString()) {
                    int from = Integer.parseInt(db.getString());
                    int to = Math.min(getIndex(), rows.size() - 1);
                    if (from >= 0 && from < rows.size() && to >= 0 && from != to) {
                        Row r = rows.remove(from);
                        rows.add(to, r);
                        list.getSelectionModel().select(to);
                        list.refresh();   // which squash ends a group may have changed
                    }
                    ok = true;
                }
                e.setDropCompleted(ok);
                e.consume();
            });
        }

        private void editMessage(Row r) {
            boolean squash = r.action.get() == RebaseAction.SQUASH;
            String initial = r.newMessage != null ? r.newMessage
                    : squash ? defaultSquashMessage(rows, rows.indexOf(r)) : fullMessage(r.commit);
            TextArea area = new TextArea(initial);
            area.setPrefRowCount(8);
            area.setPrefColumnCount(60);
            Dialog<ButtonType> d = new Dialog<>();
            d.setTitle((squash ? "Squash message " : "Reword ") + r.commit.shortHash());
            d.setHeaderText(squash ? "Message of the squashed commits" : "New commit message");
            d.getDialogPane().setContent(area);
            d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
            boolean ok = d.showAndWait().filter(b -> b == ButtonType.OK).isPresent();
            String text = area.getText().strip();
            if (ok && !text.isEmpty()) {
                r.newMessage = text;
            } else if (r.action.get() == RebaseAction.REWORD && r.newMessage == null) {
                r.action.set(RebaseAction.PICK);   // cancelled before any text was entered
            }
        }

        @Override
        protected void updateItem(Row r, boolean empty) {
            super.updateItem(r, empty);
            if (empty || r == null) {
                setGraphic(null);
                return;
            }
            updating = true;
            combo.setValue(r.action.get());
            updating = false;
            hash.setText(r.commit.shortHash());
            subject.setText(r.commit.subject());
            RebaseAction action = r.action.get();
            boolean editable = action == RebaseAction.REWORD
                    || (action == RebaseAction.SQUASH && lastSquashOfGroup(rows, getIndex()));
            edited.setVisible(editable);
            edited.setManaged(editable);
            if (editable) edited.setText(r.newMessage != null ? "✎ message edited" : "✎ message");
            subject.setStyle(r.action.get() == RebaseAction.DROP ? "-fx-strikethrough: true; -fx-opacity: 0.5;" : "");
            setGraphic(box);
        }
    }
}
