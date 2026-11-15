package com.github.gitguiorg.gitgui.ui.view;

import com.github.gitguiorg.gitgui.model.Commit;
import java.util.List;
import java.util.Optional;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

/** Small modal dialogs shared by the views. */
final class Dialogs {
    record BranchRequest(String name, boolean checkout) {}

    /** @param localOnly true for .git/info/exclude, false for the committed .gitignore */
    record IgnoreRequest(String pattern, boolean localOnly) {}

    record MergeOptions(boolean noFf, boolean squash) {}

    private Dialogs() {}

    static Optional<String> ask(String title, String header, String initial) {
        TextInputDialog d = new TextInputDialog(initial);
        d.setTitle(title);
        d.setHeaderText(header);
        return d.showAndWait().map(String::strip).filter(s -> !s.isEmpty());
    }

    /** Like {@link #ask} but accepts an empty answer, for optional values such as a worktree lock reason. */
    static Optional<String> askOptional(String title, String header, String initial) {
        TextInputDialog d = new TextInputDialog(initial);
        d.setTitle(title);
        d.setHeaderText(header);
        return d.showAndWait().map(String::strip);
    }

    static boolean confirm(String title, String message) {
        Alert a = new Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.OK, ButtonType.CANCEL);
        a.setTitle(title);
        a.setHeaderText(title);
        return a.showAndWait().filter(b -> b == ButtonType.OK).isPresent();
    }

    static Optional<String> choose(String title, String header, List<String> items) {
        if (items.isEmpty()) return Optional.empty();
        ChoiceDialog<String> d = new ChoiceDialog<>(items.get(0), items);
        d.setTitle(title);
        d.setHeaderText(header);
        return d.showAndWait();
    }

    static Optional<BranchRequest> askBranch(String from) {
        TextField name = new TextField();
        name.setPromptText("Branch name");
        CheckBox checkout = new CheckBox("Checkout the new branch");
        checkout.setSelected(true);
        GridPane g = grid();
        g.add(new Label("Name"), 0, 0);
        g.add(name, 1, 0);
        g.add(checkout, 1, 1);
        Dialog<ButtonType> d = dialog("New branch", "Create a branch at " + from, g);
        return d.showAndWait().filter(b -> b == ButtonType.OK && !name.getText().isBlank())
                .map(b -> new BranchRequest(name.getText().strip(), checkout.isSelected()));
    }

    /** @return {name, message}; an empty message means a lightweight tag */
    static Optional<String[]> askTag() {
        TextField name = new TextField();
        name.setPromptText("Tag name");
        TextArea message = new TextArea();
        message.setPromptText("Message (optional — creates an annotated tag)");
        message.setPrefRowCount(4);
        GridPane g = grid();
        g.add(new Label("Name"), 0, 0);
        g.add(name, 1, 0);
        g.add(new Label("Message"), 0, 1);
        g.add(message, 1, 1);
        Dialog<ButtonType> d = dialog("New tag", "Create a tag", g);
        return d.showAndWait().filter(b -> b == ButtonType.OK && !name.getText().isBlank())
                .map(b -> new String[]{name.getText().strip(), message.getText().strip()});
    }

    /** @return {url, path} of a new submodule */
    static Optional<String[]> askSubmodule() {
        TextField url = new TextField();
        url.setPromptText("https://host/owner/repo.git");
        url.setPrefColumnCount(40);
        TextField path = new TextField();
        path.setPromptText("libs/lib");
        GridPane g = grid();
        g.add(new Label("URL"), 0, 0);
        g.add(url, 1, 0);
        g.add(new Label("Path"), 0, 1);
        g.add(path, 1, 1);
        Dialog<ButtonType> d = dialog("Add submodule", "Add a submodule", g);
        return d.showAndWait().filter(b -> b == ButtonType.OK && !url.getText().isBlank() && !path.getText().isBlank())
                .map(b -> new String[]{url.getText().strip(), path.getText().strip()});
    }

    /** @return {name, url} */
    static Optional<String[]> askNameUrl(String title, String initialName, String initialUrl) {
        TextField name = new TextField(initialName);
        name.setPromptText("origin");
        TextField url = new TextField(initialUrl);
        url.setPromptText("https://host/owner/repo.git");
        url.setPrefColumnCount(40);
        GridPane g = grid();
        g.add(new Label("Name"), 0, 0);
        g.add(name, 1, 0);
        g.add(new Label("URL"), 0, 1);
        g.add(url, 1, 1);
        Dialog<ButtonType> d = dialog(title, title, g);
        return d.showAndWait().filter(b -> b == ButtonType.OK && !name.getText().isBlank() && !url.getText().isBlank())
                .map(b -> new String[]{name.getText().strip(), url.getText().strip()});
    }

    /** @return {name, email} to write into the repository-local git config */
    static Optional<String[]> askIdentity(String name, String email) {
        TextField n = new TextField(name);
        n.setPromptText("Name Surname");
        TextField e = new TextField(email);
        e.setPromptText("name@example.com");
        e.setPrefColumnCount(32);
        GridPane g = grid();
        g.add(new Label("Author name"), 0, 0);
        g.add(n, 1, 0);
        g.add(new Label("Author email"), 0, 1);
        g.add(e, 1, 1);
        Dialog<ButtonType> d = dialog("Identity", "Author recorded by this repository", g);
        return d.showAndWait().filter(b -> b == ButtonType.OK && !n.getText().isBlank() && !e.getText().isBlank())
                .map(b -> new String[]{n.getText().strip(), e.getText().strip()});
    }

    /** Options for a merge: always create a commit ({@code --no-ff}) or only stage the changes ({@code --squash}). */
    static Optional<MergeOptions> askMerge(String subject) {
        CheckBox noFf = new CheckBox("No fast-forward (--no-ff): always create a merge commit");
        noFf.setSelected(true);
        CheckBox squash = new CheckBox("Squash (--squash): stage the changes without committing");
        squash.selectedProperty().addListener((o, a, on) -> noFf.setDisable(on));
        VBox content = new VBox(8, new Label("Merge " + subject + " into the current branch"), noFf, squash);
        content.setPadding(new Insets(8));
        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle("Merge");
        d.setHeaderText("Merge options");
        d.getDialogPane().setContent(content);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        return d.showAndWait().filter(b -> b == ButtonType.OK)
                .map(b -> new MergeOptions(noFf.isSelected(), squash.isSelected()));
    }

    /** @return the message of the squashed commit */
    static Optional<String> askSquash(List<Commit> commits, String message) {
        TextArea list = new TextArea();
        list.setEditable(false);
        list.setPrefRowCount(Math.min(8, Math.max(3, commits.size())));
        StringBuilder preview = new StringBuilder();
        for (Commit c : commits) preview.append(c.shortHash()).append("  ").append(c.subject()).append('\n');
        list.setText(preview.toString().strip());
        TextArea text = new TextArea(message);
        text.setPrefRowCount(6);
        text.setWrapText(true);
        VBox content = new VBox(8, new Label("These commits will be folded into one:"), list,
                new Label("Message of the squashed commit"), text);
        content.setPadding(new Insets(8));
        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle("Squash");
        d.setHeaderText("Squash commits");
        d.getDialogPane().setContent(content);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        return d.showAndWait().filter(b -> b == ButtonType.OK && !text.getText().isBlank())
                .map(b -> text.getText());
    }

    /** @return {author, date}; empty strings clear the overrides */
    static Optional<String[]> askAuthor(String author, String date) {
        TextField a = new TextField(author);
        a.setPromptText("Name <email>");
        a.setPrefColumnCount(32);
        TextField d = new TextField(date);
        d.setPromptText("2024-01-31T10:00:00+01:00");
        d.setPrefColumnCount(32);
        GridPane g = grid();
        g.add(new Label("Author"), 0, 0);
        g.add(a, 1, 0);
        g.add(new Label("Date"), 0, 1);
        g.add(d, 1, 1);
        Label hint = new Label("Leave a field empty to use the configured value.");
        hint.setOpacity(0.7);
        g.add(hint, 0, 2, 2, 1);
        Dialog<ButtonType> dlg = dialog("Author override", "Optional author and date for this commit", g);
        return dlg.showAndWait().filter(b -> b == ButtonType.OK)
                .map(b -> new String[]{a.getText().strip(), d.getText().strip()});
    }

    /** Ignore rule: editable pattern plus the choice between the repository .gitignore and the clone-local exclude. */
    static Optional<IgnoreRequest> askIgnore(String header, String pattern) {
        TextField field = new TextField(pattern);
        field.setPrefColumnCount(32);
        RadioButton repo = new RadioButton("Repository (.gitignore, shared by the project)");
        RadioButton local = new RadioButton("This clone only (.git/info/exclude)");
        ToggleGroup group = new ToggleGroup();
        repo.setToggleGroup(group);
        local.setToggleGroup(group);
        repo.setSelected(true);
        VBox content = new VBox(8, new Label("Pattern"), field, repo, local);
        content.setPadding(new Insets(8));
        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle("Ignore");
        d.setHeaderText(header);
        d.getDialogPane().setContent(content);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        return d.showAndWait().filter(b -> b == ButtonType.OK && !field.getText().isBlank())
                .map(b -> new IgnoreRequest(field.getText().strip(), local.isSelected()));
    }

    private static GridPane grid() {
        GridPane g = new GridPane();
        g.setHgap(8);
        g.setVgap(8);
        g.setPadding(new Insets(8));
        return g;
    }

    private static Dialog<ButtonType> dialog(String title, String header, GridPane content) {
        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle(title);
        d.setHeaderText(header);
        d.getDialogPane().setContent(content);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        return d;
    }
}
