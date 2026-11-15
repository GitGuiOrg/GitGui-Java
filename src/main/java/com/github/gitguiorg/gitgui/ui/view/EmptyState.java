package com.github.gitguiorg.gitgui.ui.view;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.javafx.FontIcon;

/** Empty state with a title, a description and an optional action button. GitButler-style dark theme. */
public final class EmptyState extends VBox {
    public EmptyState(String icon, String title, String subtitle, String buttonText, Runnable onAction) {
        setAlignment(Pos.CENTER);
        setSpacing(10);
        setPadding(new Insets(24));
        getStyleClass().add("empty-state");

        FontIcon graphic = new FontIcon(icon);
        graphic.setIconSize(48);
        graphic.setIconColor(javafx.scene.paint.Color.web("#6e7681"));
        graphic.setOpacity(0.6);

        Label titleLabel = new Label(title);
        titleLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #e6edf3;");
        titleLabel.setWrapText(true);

        Label subtitleLabel = new Label(subtitle == null ? "" : subtitle);
        subtitleLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #8b949e;");
        subtitleLabel.setWrapText(true);
        subtitleLabel.setMaxWidth(340);

        getChildren().addAll(graphic, titleLabel, subtitleLabel);

        if (buttonText != null && !buttonText.isBlank() && onAction != null) {
            Button button = new Button(buttonText);
            button.getStyleClass().add("button");
            button.setOnAction(e -> onAction.run());
            button.setPadding(new Insets(6, 14, 6, 14));
            getChildren().add(button);
        }
    }
}
