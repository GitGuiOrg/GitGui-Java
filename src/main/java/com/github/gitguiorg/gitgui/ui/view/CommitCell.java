package com.github.gitguiorg.gitgui.ui.view;

import com.github.gitguiorg.gitgui.model.Commit;
import com.github.gitguiorg.gitgui.ui.viewmodel.LogViewModel;
import com.github.gitguiorg.gitgui.ui.viewmodel.LogViewModel.CommitRow;
import java.util.function.BiConsumer;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

/** One history row: graph | ref badges + subject | author | date | short hash. */
final class CommitCell extends ListCell<CommitRow> {
    static final double ROW_H = 24;
    private final LogViewModel log;
    private final GraphCanvas canvas = new GraphCanvas();
    private final HBox refs = new HBox(4);
    static String stackColor(int stack) { return STACK_COLORS[(Math.max(stack, 1) - 1) % STACK_COLORS.length]; }
    private static final String[] STACK_COLORS = {"#8b5cf6", "#6366f1", "#a855f7", "#c084fc", "#d8b4fe", "#4f46e5"};
    private final Label stackTag = new Label();
    private final Label star = new Label("★");
    private final Label subject = new Label();
    private final Label author = new Label();
    private final Label date = new Label();
    private final Label bisectMark = new Label();
    private final Label signedMark = new Label();
    private final Label hash = new Label();
    private final HBox root = new HBox(6);

    CommitCell(LogViewModel log, BiConsumer<Commit, javafx.scene.input.ContextMenuEvent> onMenu, Runnable onNearEnd,
               java.util.function.IntSupplier sizeSupplier) {
        this.log = log;
        refs.setAlignment(Pos.CENTER_LEFT);
        subject.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(subject, Priority.ALWAYS);
        author.setPrefWidth(150);
        author.setMinWidth(150);
        author.setOpacity(0.75);
        date.setPrefWidth(120);
        date.setMinWidth(120);
        date.setOpacity(0.75);
        star.setStyle("-fx-font-size: 12px; -fx-text-fill: #e0b400;");
        star.setMinWidth(Region.USE_PREF_SIZE);
        bisectMark.setStyle("-fx-font-size: 10px; -fx-font-weight: bold;");
        bisectMark.setMinWidth(Region.USE_PREF_SIZE);
        signedMark.setStyle("-fx-font-size: 11px; -fx-text-fill: #5cb85c;");
        signedMark.setMinWidth(Region.USE_PREF_SIZE);
        stackTag.setMinWidth(Region.USE_PREF_SIZE);
        hash.setPrefWidth(60);
        hash.setMinWidth(60);
        hash.setStyle("-fx-font-family: monospace;");
        hash.setOpacity(0.6);
        root.setAlignment(Pos.CENTER_LEFT);
        root.setPadding(new Insets(0, 8, 0, 0));
        root.getChildren().addAll(canvas, star, stackTag, refs, subject, author, date, bisectMark, signedMark, hash);
        setPadding(Insets.EMPTY);
        setPrefHeight(ROW_H);
        setOnContextMenuRequested(e -> {
            if (!isEmpty() && getItem() != null) { onMenu.accept(getItem().commit(), e); e.consume(); }
        });
        this.onNearEnd = onNearEnd;
        this.sizeSupplier = sizeSupplier;
        log.highlightedStackProperty().addListener((o, a, b) -> applyHighlight());
        log.stackTickProperty().addListener((o, a, b) -> applyHighlight());
    }

    private final Runnable onNearEnd;
    private final java.util.function.IntSupplier sizeSupplier;

    @Override
    protected void updateItem(CommitRow row, boolean empty) {
        super.updateItem(row, empty);
        if (empty || row == null) {
            setGraphic(null);
            return;
        }
        Commit c = row.commit();
        canvas.draw(row.graph(), log.graphLanesProperty().get(), ROW_H, c.isMerge(), log.stackOf(c.hash()));
        refs.getChildren().setAll(c.refs().stream().map(RefBadge::create).toList());
        int stack = log.stackOf(c.hash());
        stackTag.setVisible(stack > 0);
        stackTag.setManaged(stack > 0);
        if (stack > 0) {
            String color = CommitCell.stackColor(stack);
            stackTag.setText("S" + stack);
            stackTag.setStyle("-fx-font-size: 10px; -fx-font-weight: bold; -fx-text-fill: white; -fx-padding: 0 5 0 5;"
                    + " -fx-background-radius: 4; -fx-background-color: " + color + ";");
            stackTag.setTooltip(new Tooltip("Stack S" + stack));
        }
        boolean isStarred = log.isStarred(c.hash());
        star.setVisible(isStarred);
        star.setManaged(isStarred);
        subject.setText(c.subject());
        author.setText(c.authorName());
        date.setText(com.github.gitguiorg.gitgui.util.DateFormats.dateTime(c.authorDate()));
        String bisect = log.isBisectBad(c.hash()) ? "bad" : log.isBisectGood(c.hash()) ? "good" : "";
        bisectMark.setText(bisect);
        bisectMark.setStyle("-fx-font-size: 10px; -fx-font-weight: bold; -fx-text-fill: "
                + ("bad".equals(bisect) ? "#d9534f" : "#5cb85c") + ";");
        bisectMark.setVisible(!bisect.isEmpty());
        bisectMark.setManaged(!bisect.isEmpty());
        boolean hasSignature = c.signed();
        signedMark.setVisible(hasSignature);
        signedMark.setManaged(hasSignature);
        signedMark.setText(hasSignature ? ("B".equals(c.signature()) ? "✗" : "✓") : "");
        signedMark.setStyle(hasSignature && "B".equals(c.signature())
                ? "-fx-font-size: 11px; -fx-text-fill: #d9534f;"
                : "-fx-font-size: 11px; -fx-text-fill: #5cb85c;");
        signedMark.setTooltip(hasSignature ? new Tooltip(c.signatureLabel()) : null);
        hash.setText(c.shortHash());
        setGraphic(root);
        applyHighlight();
        if (getIndex() >= sizeSupplier.getAsInt() - 40) onNearEnd.run();
    }

    private void applyHighlight() {
        CommitRow r = getItem();
        int rowStack = r == null ? 0 : log.stackOf(r.commit().hash());
        int hl = log.highlightedStackProperty().get();
        boolean on = hl > 0 && rowStack == hl && !isSelected();
        if (on) {
            if (!getStyleClass().contains("stack-highlight")) getStyleClass().add("stack-highlight");
        } else {
            getStyleClass().remove("stack-highlight");
        }
    }
}
