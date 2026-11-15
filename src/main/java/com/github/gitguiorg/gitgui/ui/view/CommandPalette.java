package com.github.gitguiorg.gitgui.ui.view;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * Command palette overlay: a searchable list of global and per-repository actions, opened with Ctrl+K.
 * Entries are filtered as the user types, navigable with the keyboard and the most used ones move to the top.
 */
final class CommandPalette extends StackPane {
    /** One entry of the palette: label, category, optional shortcut and the action it runs. */
    record Command(String title, String category, String shortcut, Runnable action) {
        /** Identity used to count how often the command is used. */
        String id() { return category + "/" + title; }

        /** True when every token of the query appears in the title, category or shortcut. */
        boolean matches(String query) {
            String haystack = (title + " " + category + " " + shortcut).toLowerCase(Locale.ROOT);
            for (String token : query.split(" ")) {
                if (!haystack.contains(token)) return false;
            }
            return true;
        }
    }

    private final Supplier<List<Command>> source;
    private final TextField search = new TextField();
    private final ListView<Command> list = new ListView<>();
    private final Map<String, Integer> uses = new HashMap<>();

    CommandPalette(Supplier<List<Command>> source) {
        this.source = source;
        getStyleClass().add("command-palette-overlay");
        setVisible(false);
        setManaged(false);

        Region dimmer = new Region();
        dimmer.getStyleClass().add("command-palette-dimmer");
        dimmer.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY) hide();
        });

        search.setPromptText("Type a command…");
        search.getStyleClass().add("command-palette-search");
        search.textProperty().addListener((o, a, text) -> refresh(text));
        search.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                hide();
            } else if (e.getCode() == KeyCode.DOWN) {
                move(1);
            } else if (e.getCode() == KeyCode.UP) {
                move(-1);
            } else if (e.getCode() == KeyCode.ENTER) {
                runSelected();
            } else {
                return;
            }
            e.consume();
        });

        list.setPlaceholder(new Label("No matching command"));
        list.setCellFactory(v -> new Cell());
        list.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY) runSelected();
        });

        Label hint = new Label("↑↓ navigate   Enter run   Esc close");
        hint.getStyleClass().add("command-palette-hint");

        VBox card = new VBox(8, search, list, hint);
        card.getStyleClass().add("command-palette");
        card.setPrefWidth(620);
        card.setMaxWidth(620);
        list.setPrefHeight(320);
        list.setMinHeight(140);
        list.setMaxHeight(320);

        getChildren().addAll(dimmer, card);
        StackPane.setAlignment(card, Pos.CENTER);
        addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (isVisible() && e.getCode() == KeyCode.ESCAPE) {
                hide();
                e.consume();
            }
        });
    }

    /** Shows the palette with every available command, keeping the typed query out of the way. */
    void show() {
        setVisible(true);
        setManaged(true);
        toFront();
        search.clear();
        refresh("");
        search.requestFocus();
    }

    /** Hides the palette without touching the command list. */
    void hide() {
        setVisible(false);
        setManaged(false);
    }

    private void refresh(String query) {
        List<Command> filtered = filter(source.get(), query, uses);
        int keep = list.getSelectionModel().getSelectedIndex();
        list.getItems().setAll(filtered);
        if (filtered.isEmpty()) return;
        int select = keep >= 0 && keep < filtered.size() ? keep : 0;
        list.getSelectionModel().select(select);
        list.scrollTo(select);
    }

    private void move(int delta) {
        int size = list.getItems().size();
        if (size == 0) return;
        int next = Math.floorMod(list.getSelectionModel().getSelectedIndex() + delta, size);
        list.getSelectionModel().select(next);
        list.scrollTo(next);
    }

    private void runSelected() {
        Command command = list.getSelectionModel().getSelectedItem();
        if (command == null) return;
        uses.merge(command.id(), 1, Integer::sum);
        hide();
        command.action().run();
    }

    /**
     * Commands matching the query, most used first; a stable sort keeps the declared order for equal usage.
     * Pure so it can be tested without initialising the FX toolkit.
     */
    static List<Command> filter(List<Command> all, String query, Map<String, Integer> uses) {
        String q = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        List<Command> out = new ArrayList<>();
        for (Command c : all) {
            if (q.isEmpty() || c.matches(q)) out.add(c);
        }
        out.sort(Comparator.comparingInt((Command c) -> uses.getOrDefault(c.id(), 0)).reversed());
        return out;
    }

    /** Row of the list: title, spacer, category pill and shortcut. */
    private final class Cell extends ListCell<Command> {
        private final Label title = new Label();
        private final Label category = new Label();
        private final Label shortcut = new Label();
        private final HBox row = new HBox(8);

        Cell() {
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            title.getStyleClass().add("command-palette-title");
            category.getStyleClass().addAll("pill", "accent");
            shortcut.getStyleClass().add("command-palette-shortcut");
            row.getStyleClass().add("command-palette-row");
            row.setAlignment(Pos.CENTER_LEFT);
            row.getChildren().addAll(title, spacer, category, shortcut);
        }

        @Override
        protected void updateItem(Command item, boolean empty) {
            super.updateItem(item, empty);
            if (item == null || empty) {
                setText(null);
                setGraphic(null);
                return;
            }
            title.setText(item.title());
            category.setText(item.category());
            shortcut.setText(item.shortcut());
            setText(null);
            setGraphic(row);
        }
    }
}