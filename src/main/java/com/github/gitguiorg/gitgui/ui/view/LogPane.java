package com.github.gitguiorg.gitgui.ui.view;

import com.github.gitguiorg.gitgui.model.GitRef;
import com.github.gitguiorg.gitgui.model.LogFilter;
import com.github.gitguiorg.gitgui.ui.viewmodel.RepoViewModel;
import java.util.ArrayList;
import java.util.List;
import javafx.animation.PauseTransition;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.util.Duration;
import javafx.util.StringConverter;

/** Commit history with a search/filter bar on top. */
public final class LogPane extends BorderPane {
    private static final String ALL = "All branches";
    private static final String CURRENT = "Current branch";

    private final RepoViewModel repo;
    private final TextField search = new TextField();
    private final ComboBox<LogFilter.Mode> mode = new ComboBox<>(FXCollections.observableArrayList(LogFilter.Mode.values()));
    private final ComboBox<String> branch = new ComboBox<>();
    private final Label state = new Label();
    private boolean updating;

    public LogPane(RepoViewModel repo) {
        this.repo = repo;

        search.setPromptText("Search commits…  (Ctrl+F)");
        HBox.setHgrow(search, Priority.ALWAYS);
        mode.setValue(LogFilter.Mode.MESSAGE);
        mode.setConverter(new StringConverter<>() {
            @Override public String toString(LogFilter.Mode m) { return m == null ? "" : m.label; }
            @Override public LogFilter.Mode fromString(String s) { return null; }
        });
        fillBranches();
        Button clear = new Button(null, new org.kordamp.ikonli.javafx.FontIcon("mdi2c-close"));
        clear.setTooltip(new Tooltip("Clear search and filters (Esc)"));
        clear.setOnAction(e -> clearFilter());
        state.setOpacity(0.7);

        PauseTransition debounce = new PauseTransition(Duration.millis(350));
        debounce.setOnFinished(e -> apply());
        search.textProperty().addListener((o, a, b) -> debounce.playFromStart());
        search.setOnAction(e -> { debounce.stop(); apply(); });
        search.setOnKeyPressed(e -> { if (e.getCode() == KeyCode.ESCAPE) { clearFilter(); e.consume(); } });
        mode.setOnAction(e -> { if (search.getText().isBlank()) return; apply(); });
        branch.setOnAction(e -> { if (!updating) apply(); });
        repo.refs().addListener((ListChangeListener<GitRef>) c -> fillBranches());

        CheckBox starredOnly = new CheckBox("Starred");
        starredOnly.setTooltip(new Tooltip("Show only the commits you starred in this repository"));
        starredOnly.selectedProperty().addListener((o, a, on) -> {
            repo.log().setStarredOnly(on);
            state.setText(on ? "starred only — graph hidden" : "");
        });
        HBox bar = new HBox(6, search, mode, branch, starredOnly, clear, state);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(3, 6, 3, 6));
        setTop(bar);
        setCenter(new LogView(repo));
    }

    public void focusSearch() {
        search.requestFocus();
        search.selectAll();
    }

    private void fillBranches() {
        String keep = branch.getValue();
        List<String> items = new ArrayList<>(List.of(ALL, CURRENT));
        repo.refs().stream().filter(r -> r.type() == GitRef.Type.LOCAL_BRANCH).map(GitRef::shortName)
                .sorted(String.CASE_INSENSITIVE_ORDER).forEach(items::add);
        updating = true;
        branch.getItems().setAll(items);
        branch.setValue(keep != null && items.contains(keep) ? keep : ALL);
        updating = false;
    }

    private void clearFilter() {
        search.clear();
        updating = true;
        branch.setValue(ALL);
        updating = false;
        apply();
    }

    private void apply() {
        String b = branch.getValue();
        String branchFilter = b == null || b.equals(ALL) ? null : b.equals(CURRENT) ? "HEAD" : b;
        LogFilter f = new LogFilter(mode.getValue(), search.getText().strip(), branchFilter);
        repo.log().setFilter(f);
        state.setText(f.hasText() ? "filtered — graph hidden" : f.isActive() ? "filtered by branch" : "");
    }
}
