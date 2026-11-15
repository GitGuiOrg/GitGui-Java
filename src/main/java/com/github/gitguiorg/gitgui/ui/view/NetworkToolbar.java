package com.github.gitguiorg.gitgui.ui.view;

import com.github.gitguiorg.gitgui.model.RepoStatus;
import com.github.gitguiorg.gitgui.ui.viewmodel.NetworkViewModel;
import com.github.gitguiorg.gitgui.ui.viewmodel.RepoViewModel;
import com.github.gitguiorg.gitgui.util.Texts;
import java.util.List;
import java.util.Optional;
import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Separator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import org.kordamp.ikonli.javafx.FontIcon;

/** Fetch / Pull / Push buttons with progress bar, phase label and Cancel. */
public final class NetworkToolbar extends HBox {
    public NetworkToolbar(RepoViewModel repo) {
        NetworkViewModel net = repo.network();
        net.setUi(new NetworkViewModel.Ui() {
            @Override
            public Optional<String> chooseRemote(List<String> remotes) {
                ChoiceDialog<String> d = new ChoiceDialog<>(remotes.get(0), remotes);
                d.setTitle("Push");
                d.setHeaderText("This branch has no upstream. Push to which remote?");
                d.setContentText("Remote:");
                return d.showAndWait();
            }

            @Override
            public boolean offerPull(String gitMessage) {
                ButtonType pull = new ButtonType("Pull", javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
                Alert a = new Alert(Alert.AlertType.ERROR, null, pull, ButtonType.CLOSE);
                a.setTitle("Push rejected");
                a.setHeaderText("The remote has commits you don't have. Pull first?");
                a.setContentText(Texts.ellipsis(gitMessage, 400));
                return a.showAndWait().filter(b -> b == pull).isPresent();
            }
        });

        Button fetch = new Button("Fetch", new FontIcon("mdi2r-refresh"));
        fetch.setOnAction(e -> net.fetch(false));
        Button pull = new Button("Pull", new FontIcon("mdi2a-arrow-down"));
        Button push = new Button("Push", new FontIcon("mdi2a-arrow-up"));
        push.setOnAction(e -> net.push());
        push.textProperty().bind(Bindings.createStringBinding(() -> {
            RepoStatus s = repo.statusProperty().get();
            return s != null && s.ahead() > 0 ? "Push ↑" + s.ahead() : "Push";
        }, repo.statusProperty()));
        // GitButler workspace: Pull updates the workspace with `but pull`; Push is done per stack in the GitButler tab.
        pull.setOnAction(e -> {
            if (repo.isButWorkspace()) repo.gitButler().pullWorkspace();
            else net.pull();
        });
        pull.textProperty().bind(Bindings.createStringBinding(() -> {
            if (repo.isButWorkspace()) {
                var bs = repo.gitButler().statusProperty().get();
                return bs != null && bs.behind() > 0 ? "Pull ↓" + bs.behind() : "Pull";
            }
            RepoStatus s = repo.statusProperty().get();
            return s != null && s.behind() > 0 ? "Pull ↓" + s.behind() : "Pull";
        }, repo.statusProperty(), repo.butWorkspaceProperty(), repo.gitButler().statusProperty()));
        for (Button b : List.of(fetch, pull)) b.disableProperty().bind(net.busyProperty());
        push.disableProperty().bind(net.busyProperty().or(repo.butWorkspaceProperty()));
        push.tooltipProperty().bind(Bindings.when(repo.butWorkspaceProperty())
                .then(new javafx.scene.control.Tooltip("GitButler workspace: push each stack from the GitButler tab"))
                .otherwise((javafx.scene.control.Tooltip) null));
        Label butInfo = new Label();
        butInfo.setStyle("-fx-font-size: 11px; -fx-padding: 1 6 1 6; -fx-background-radius: 3; -fx-background-color: #8957e5; -fx-text-fill: white;");
        butInfo.visibleProperty().bind(repo.butWorkspaceProperty());
        butInfo.managedProperty().bind(repo.butWorkspaceProperty());
        butInfo.textProperty().bind(Bindings.createStringBinding(() -> {
            var bs = repo.gitButler().statusProperty().get();
            if (bs == null) return "GitButler";
            StringBuilder sb = new StringBuilder("GitButler · ").append(bs.stacks().size()).append(" stack");
            if (!bs.uncommittedChanges().isEmpty()) sb.append(" · ").append(bs.uncommittedChanges().size()).append(" changes");
            if (bs.conflictedCommits() > 0) sb.append(" · ⚠ ").append(bs.conflictedCommits()).append(" conflicts");
            return sb.toString();
        }, repo.gitButler().statusProperty()));

        ProgressBar bar = new ProgressBar();
        bar.setPrefWidth(160);
        bar.progressProperty().bind(net.progressProperty());
        Label phase = new Label();
        phase.textProperty().bind(net.phaseProperty());
        Button cancel = new Button("Cancel", new FontIcon("mdi2c-close"));
        cancel.setOnAction(e -> net.cancel());
        for (var n : List.of(bar, phase, cancel)) {
            n.visibleProperty().bind(net.busyProperty());
            n.managedProperty().bind(net.busyProperty());
        }

        Button openFolder = new Button(null, new FontIcon("mdi2f-folder-open-outline"));
        openFolder.setTooltip(new javafx.scene.control.Tooltip("Open the project folder: " + repo.root()));
        openFolder.setOnAction(e -> openProjectFolder(repo));

        // Branch / ahead-behind text, network warning and the git command log live here, replacing the bottom status bar.
        Label status = new Label();
        status.setOpacity(0.8);
        status.setText(repo.statusText());
        repo.statusProperty().addListener((o, a, b) -> status.setText(repo.statusText()));
        Label warning = new Label();
        warning.setStyle("-fx-text-fill: #d29922;");
        warning.textProperty().bind(net.warningProperty());
        Button commands = new Button(null, new FontIcon("mdi2c-console"));
        commands.setTooltip(new javafx.scene.control.Tooltip("Git commands: what the app ran, with exit code and stderr"));
        commands.setOnAction(e -> CommandLogDialog.show(getScene() == null ? null : getScene().getWindow(), repo));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        setSpacing(6);
        setAlignment(Pos.CENTER_LEFT);
        setPadding(new Insets(3, 6, 3, 6));
        getChildren().addAll(fetch, pull, push, new Separator(javafx.geometry.Orientation.VERTICAL), bar, phase, cancel,
                butInfo, spacer, warning, status, commands, openFolder);
    }

    /** Opens the repository root in the system file manager. */
    private static void openProjectFolder(RepoViewModel repo) {
        try {
            com.github.gitguiorg.gitgui.util.SystemActions.open(repo.root());
        } catch (Exception e) {
            repo.reportError(e);
        }
    }
}
