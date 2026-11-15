package com.github.gitguiorg.gitgui.ui.view;

import com.github.gitguiorg.gitgui.model.Commit;
import com.github.gitguiorg.gitgui.ui.viewmodel.LogViewModel;
import com.github.gitguiorg.gitgui.util.Hashes;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;

/** Details of the selected commit (message, author, parents). The file diff arrives in the next step. */
public final class CommitDetailView extends VBox {
    private final Label subject = new Label();
    private final Label meta = new Label();
    private final Label signature = new Label();
    private final Label hash = new Label();
    private final TextArea body = new TextArea();

    public CommitDetailView(LogViewModel log) {
        setSpacing(3);
        setPadding(new Insets(6, 8, 6, 8));
        subject.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");
        subject.setWrapText(true);
        hash.setStyle("-fx-font-family: monospace;");
        hash.setOpacity(0.7);
        meta.setOpacity(0.8);
        signature.setOpacity(0.8);
        body.setEditable(false);
        body.setWrapText(true);
        body.setPrefRowCount(3);
        getChildren().addAll(subject, new javafx.scene.layout.HBox(12, meta, signature), hash, body);
        log.selectedProperty().addListener((o, a, c) -> show(c));
        show(null);
    }

    private void show(Commit c) {
        boolean has = c != null;
        subject.setText(has ? c.subject() : "Select a commit");
        meta.setText(has ? c.authorName() + " <" + c.authorEmail() + ">  ·  "
                + com.github.gitguiorg.gitgui.util.DateFormats.dateTimeSeconds(c.authorDate()) : "");
        hash.setText(has ? c.hash() + (c.parents().isEmpty() ? "" : "  ←  " + String.join(", ",
                c.parents().stream().map(Hashes::shortHash).toList())) : "");
        signature.setText(has ? c.signatureLabel() : "");
        signature.setStyle(has && "B".equals(c.signature()) ? "-fx-text-fill: #d9534f;" : "");
        signature.setVisible(has);
        signature.setManaged(has);
        body.setText(has ? c.body() : "");
        body.setVisible(has);
        body.setManaged(has);
    }
}