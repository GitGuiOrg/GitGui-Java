package com.github.gitguiorg.gitgui.ui.view;

import com.github.gitguiorg.gitgui.model.GitRef;
import java.io.File;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.stage.DirectoryChooser;

/** Collects the parameters of {@code git worktree add}. */
public final class WorktreeDialog {
    private WorktreeDialog() {}

    public record Result(Path path, String newBranch, String startPoint) {}

    /** @param usedBranches branches already checked out in another worktree, left out of the choices */
    public static Optional<Result> show(Path repoRoot, List<GitRef> refs, java.util.Set<String> usedBranches) {
        Dialog<Result> d = new Dialog<>();
        d.setTitle("Add worktree");
        d.setHeaderText("Check out another branch in a separate folder");
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        TextField path = new TextField();
        path.setPromptText("Folder for the new worktree");
        Button browse = new Button("Browse…");
        CheckBox create = new CheckBox("Create a new branch");
        TextField branch = new TextField();
        branch.setPromptText("new-branch-name");
        branch.setDisable(true);
        List<String> choices = new ArrayList<>();
        choices.add("HEAD");
        List<String> localBranches = new ArrayList<>();
        for (GitRef r : refs) {
            if (r.type() == GitRef.Type.LOCAL_BRANCH) {
                localBranches.add(r.shortName());
                if (!usedBranches.contains(r.shortName())) choices.add(r.shortName());
            } else if (r.type() == GitRef.Type.REMOTE_BRANCH) {
                choices.add(r.shortName());
            }
        }
        ComboBox<String> base = new ComboBox<>();
        base.getItems().setAll(choices);
        base.setEditable(true);
        base.setValue("HEAD");
        base.setMaxWidth(Double.MAX_VALUE);
        Label baseLabel = new Label("Check out");
        Label detachedHint = new Label("Not a branch: the worktree would have a detached HEAD unless a new branch is created.");
        detachedHint.setStyle("-fx-text-fill: #d29922;");
        detachedHint.setWrapText(true);
        create.selectedProperty().addListener((o, a, on) -> {
            branch.setDisable(!on);
            baseLabel.setText(on ? "Start from" : "Check out");
            if (on && branch.getText().isBlank()) {
                String v = base.getValue() == null ? "" : base.getValue().strip();
                if (!v.isEmpty() && !v.equals("HEAD") && !localBranches.contains(v)) {
                    branch.setText(v.replaceFirst("^[^/]+/", ""));   // "origin/feature/x" -> "feature/x"
                }
            }
        });
        Runnable checkDetached = () -> {
            String v = base.getValue() == null ? "" : base.getValue().strip();
            boolean detached = !v.isEmpty() && !v.equals("HEAD") && !localBranches.contains(v);
            detachedHint.setVisible(detached);
            detachedHint.setManaged(detached);
        };
        base.valueProperty().addListener((o, a, b) -> checkDetached.run());

        boolean[] pathEdited = {false};
        path.setOnKeyTyped(e -> pathEdited[0] = true);
        Runnable suggest = () -> {
            if (pathEdited[0]) return;
            String tag = create.isSelected() ? branch.getText().strip() : base.getValue() == null ? "" : base.getValue().strip();
            tag = tag.replaceAll("[\\\\/:*?\"<>|\\s]+", "-");
            Path parent = repoRoot.getParent();
            Path name = repoRoot.getFileName();
            if (parent == null || name == null || tag.isEmpty() || tag.equals("HEAD")) return;
            path.setText(parent.resolve(name + "-" + tag).toString());
        };
        branch.textProperty().addListener((o, a, b) -> suggest.run());
        base.valueProperty().addListener((o, a, b) -> suggest.run());
        create.selectedProperty().addListener((o, a, b) -> suggest.run());
        browse.setOnAction(e -> {
            DirectoryChooser dc = new DirectoryChooser();
            dc.setTitle("Worktree folder");
            File f = dc.showDialog(d.getDialogPane().getScene().getWindow());
            if (f != null) {
                path.setText(f.getAbsolutePath());
                pathEdited[0] = true;
            }
        });

        GridPane g = new GridPane();
        g.setHgap(8);
        g.setVgap(8);
        g.setPadding(new Insets(8));
        ColumnConstraints c1 = new ColumnConstraints();
        c1.setHgrow(Priority.ALWAYS);
        g.getColumnConstraints().addAll(new ColumnConstraints(), c1, new ColumnConstraints());
        g.add(new Label("Folder"), 0, 0);
        g.add(path, 1, 0);
        g.add(browse, 2, 0);
        g.add(create, 1, 1, 2, 1);
        g.add(new Label("Branch name"), 0, 2);
        g.add(branch, 1, 2, 2, 1);
        g.add(baseLabel, 0, 3);
        g.add(base, 1, 3, 2, 1);
        g.add(detachedHint, 0, 4, 3, 1);
        checkDetached.run();
        g.setPrefWidth(560);
        d.getDialogPane().setContent(g);

        Button ok = (Button) d.getDialogPane().lookupButton(ButtonType.OK);
        ok.disableProperty().bind(path.textProperty().isEmpty()
                .or(create.selectedProperty().and(branch.textProperty().isEmpty())));
        d.setResultConverter(bt -> {
            if (bt != ButtonType.OK) return null;
            try {
                String start = base.getValue() == null || base.getValue().isBlank() ? "HEAD" : base.getValue().strip();
                return new Result(Path.of(path.getText().strip()),
                        create.isSelected() ? branch.getText().strip() : null, start);
            } catch (InvalidPathException e) {
                return null;
            }
        });
        return d.showAndWait();
    }
}
