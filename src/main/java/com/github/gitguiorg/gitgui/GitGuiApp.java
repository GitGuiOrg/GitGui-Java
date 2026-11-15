package com.github.gitguiorg.gitgui;

import atlantafx.base.theme.PrimerDark;
import com.github.gitguiorg.gitgui.git.GitException;
import com.github.gitguiorg.gitgui.git.GitRunner;
import com.github.gitguiorg.gitgui.service.Settings;
import com.github.gitguiorg.gitgui.service.SettingsService;
import com.github.gitguiorg.gitgui.ui.view.MainView;
import com.github.gitguiorg.gitgui.ui.viewmodel.MainViewModel;
import com.github.gitguiorg.gitgui.util.AppDirs;
import com.github.gitguiorg.gitgui.util.WindowPlacement;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class GitGuiApp extends Application {
    private MainViewModel vm;
    private SettingsService settingsService;
    private MainView view;
    private Stage stage;

    @Override
    public void start(Stage stage) {
        Application.setUserAgentStylesheet(new PrimerDark().getUserAgentStylesheet());
        SettingsService settings = new SettingsService(AppDirs.configDir());
        settingsService = settings;
        Settings s = settings.get();
        com.github.gitguiorg.gitgui.util.DateFormats.configure(s.dateStyle, s.dateTimeZone);
        GitRunner runner;
        try {
            runner = new GitRunner(settings.get().gitPath);
        } catch (GitException e) {
            new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.ERROR,
                    e.getMessage() + "\nSet gitPath in settings.json.").showAndWait();
            Platform.exit();
            return;
        }
        vm = new MainViewModel(settings, runner);
        vm.setPrompter(com.github.gitguiorg.gitgui.ui.view.CredentialDialog::ask);
        com.github.gitguiorg.gitgui.ui.view.CredentialDialog.setCredentialHelperSupport(
                vm::credentialHelperConfigured, vm::enableCredentialManager);
        stage.setTitle("GitGui");
        view = new MainView(vm);
        double w = s.windowWidth >= 400 ? s.windowWidth : 1280;
        double h = s.windowHeight >= 300 ? s.windowHeight : 800;
        Scene scene = new Scene(view, w, h);
        scene.getStylesheets().add(GitGuiApp.class.getResource("/com/github/gitguiorg/gitgui/css/app.css").toExternalForm());
        stage.setScene(scene);
        if (!Double.isNaN(s.windowX) && !Double.isNaN(s.windowY)) {
            var pos = WindowPlacement.visiblePosition(s.windowX, s.windowY, w, h, screens());
            stage.setX(pos.x());
            stage.setY(pos.y());
        }
        stage.setMaximized(s.windowMaximized);
        stage.show();
        this.stage = stage;
        var args = getParameters().getUnnamed();
        if (!args.isEmpty()) {
            for (String arg : args) vm.openRepository(Path.of(arg));
        } else if (s.restoreSession) {
            for (String r : s.sessionRepos) {
                Path p = Path.of(r);
                if (Files.isDirectory(p)) vm.openRepository(p);
            }
        }
    }

    /** Available screens, the primary one first: it is where an off-screen window is moved back to. */
    private static java.util.List<WindowPlacement.Screen> screens() {
        var primary = javafx.stage.Screen.getPrimary();
        var all = new ArrayList<javafx.stage.Screen>();
        all.add(primary);
        for (var sc : javafx.stage.Screen.getScreens()) if (sc != primary) all.add(sc);
        return all.stream().map(sc -> {
            var b = sc.getVisualBounds();
            return new WindowPlacement.Screen(b.getMinX(), b.getMinY(), b.getWidth(), b.getHeight());
        }).toList();
    }

    @Override
    public void stop() {
        if (vm == null) return;
        Settings s = settingsService.get();
        if (view != null) view.saveLayout();
        if (stage != null) {
            if (!stage.isMaximized()) {
                s.windowWidth = stage.getWidth();
                s.windowHeight = stage.getHeight();
                s.windowX = stage.getX();
                s.windowY = stage.getY();
            }
            s.windowMaximized = stage.isMaximized();
        }
        var roots = new ArrayList<>(view == null ? java.util.List.<String>of()
                : view.tabRoots().stream().map(Path::toString).toList());
        s.sessionActive = vm.selectedProperty().get() == null ? null
                : vm.selectedProperty().get().session().root().toString();
        if (s.sessionActive != null) {   // reopen the active tab last, so it is selected again
            roots.remove(s.sessionActive);
            roots.add(s.sessionActive);
        }
        s.sessionRepos = roots;
        settingsService.save();
        vm.shutdown();
    }

    public static void main(String[] args) {
        launch(args);
    }
}