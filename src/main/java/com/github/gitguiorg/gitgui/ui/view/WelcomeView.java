package com.github.gitguiorg.gitgui.ui.view;

import com.github.gitguiorg.gitgui.ui.viewmodel.MainViewModel;
import java.nio.file.Path;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.javafx.FontIcon;

public final class WelcomeView extends VBox {
    private final MainViewModel vm;
    private final ListView<String> recent = new ListView<>();

    public WelcomeView(MainViewModel vm, Runnable onOpen, Runnable onClone, Runnable onInit) {
        this.vm = vm;
        setAlignment(Pos.CENTER);
        setSpacing(16);
        setPadding(new Insets(32));
        setMaxWidth(560);

        Label title = new Label("GitGui");
        title.setStyle("-fx-font-size: 28px; -fx-font-weight: bold;");

        Button open = new Button("Open repository…", new FontIcon("mdi2f-folder-open-outline"));
        open.setOnAction(e -> onOpen.run());
        Button cloneBtn = new Button("Clone repository…", new FontIcon("mdi2d-download"));
        cloneBtn.setOnAction(e -> onClone.run());
        Button init = new Button("Init repository…", new FontIcon("mdi2p-plus"));
        init.setOnAction(e -> onInit.run());

        recent.setPlaceholder(new Label("No recent repositories"));
        recent.setPrefHeight(260);
        recent.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && recent.getSelectionModel().getSelectedItem() != null) {
                vm.openRepository(Path.of(recent.getSelectionModel().getSelectedItem()));
            }
        });
        getChildren().addAll(title, new HBox(8, open, cloneBtn, init), new Label("Recent"), recent);
        ((HBox) getChildren().get(1)).setAlignment(Pos.CENTER);
        reload();
    }

    public void reload() {
        recent.getItems().setAll(vm.recentRepos());
    }
}
