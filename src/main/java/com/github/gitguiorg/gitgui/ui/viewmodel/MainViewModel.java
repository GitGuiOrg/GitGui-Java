package com.github.gitguiorg.gitgui.ui.viewmodel;

import com.github.gitguiorg.gitgui.git.GitClient;
import com.github.gitguiorg.gitgui.git.GitRunner;
import com.github.gitguiorg.gitgui.service.RepoSession;
import com.github.gitguiorg.gitgui.service.SettingsService;
import com.github.gitguiorg.gitgui.ui.Fx;
import com.github.gitguiorg.gitgui.util.RepoPaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

public final class MainViewModel {
    private final SettingsService settings;
    private final com.github.gitguiorg.gitgui.service.DiffPreferences diffPreferences;
    private volatile GitRunner runner;
    private volatile com.github.gitguiorg.gitgui.git.Prompter prompter;
    private final ObservableList<RepoViewModel> repos = FXCollections.observableArrayList();
    private final ObjectProperty<RepoViewModel> selected = new SimpleObjectProperty<>();
    private Consumer<Throwable> errorHandler = e -> {};
    private Runnable recentChangedHandler = () -> {};

    public MainViewModel(SettingsService settings, GitRunner runner) {
        this.settings = settings;
        this.runner = runner;
        this.diffPreferences = new com.github.gitguiorg.gitgui.service.DiffPreferences(settings);
    }

    /** Diff options shared by every tab, persisted in the settings file. */
    public com.github.gitguiorg.gitgui.service.DiffPreferences diffPreferences() { return diffPreferences; }

    public void setErrorHandler(Consumer<Throwable> h) { this.errorHandler = h; }

    /** Handler called on the FX thread when a path is dropped from the recent list. */
    public void setRecentChangedHandler(Runnable h) { this.recentChangedHandler = h; }

    /** Handler for credential prompts of git/ssh; applied to the current and any future git runner. */
    public void setPrompter(com.github.gitguiorg.gitgui.git.Prompter p) {
        this.prompter = p;
        runner.setPrompter(p);
    }

    public SettingsService settingsService() { return settings; }

    /** Re-creates the git runner from settings.gitPath; repositories opened afterwards use it. */
    public void applyGitPath() { runner = new GitRunner(settings.get().gitPath).setPrompter(prompter); }

    /** True when git has at least one credential helper (system, global or local) configured. */
    public boolean credentialHelperConfigured() {
        GitRunner r = new GitRunner(settings.get().gitPath);
        try {
            var c = com.github.gitguiorg.gitgui.git.GitCommand.in(Path.of(System.getProperty("user.home")))
                    .args("config", "--get-all", "credential.helper");
            return !r.runRaw(c).out().isBlank();
        } catch (RuntimeException e) {
            return true;   // cannot tell: do not nag with the hint
        }
    }

    /** Turns on Git for Windows' credential manager in the global config. */
    public void enableCredentialManager() {
        GitRunner r = new GitRunner(settings.get().gitPath);
        r.run(com.github.gitguiorg.gitgui.git.GitCommand.in(Path.of(System.getProperty("user.home")))
                .args("config", "--global", "credential.helper", "manager"));
    }

    /** Version of the configured but executable, or "not found". */
    public String butVersion() {
        try {
            return com.github.gitguiorg.gitgui.git.ButClient.versionOf(settings.get().butPath);
        } catch (RuntimeException e) {
            return "not found";
        }
    }


    /** Output of {@code git --version} for the configured executable, or a message when git cannot run. */
    public String gitVersion() {
        try {
            return runner.run(com.github.gitguiorg.gitgui.git.GitCommand.in(Path.of(System.getProperty("user.home")))
                    .args("--version")).out().strip();
        } catch (RuntimeException e) {
            return "git not found";
        }
    }

    public ObservableList<RepoViewModel> repos() { return repos; }
    public ObjectProperty<RepoViewModel> selectedProperty() { return selected; }
    public List<String> recentRepos() { return List.copyOf(settings.get().recentRepos); }

    /** Opens the repo in a new tab, or selects the existing tab if it is already open. */
    public void openRepository(Path dir) {
        if (!Files.isDirectory(dir)) {
            if (settings.removeRecent(dir)) Fx.run(recentChangedHandler);
            errorHandler.accept(new java.io.IOException("Folder not found: " + dir));
            return;
        }
        Thread.startVirtualThread(() -> {
            try {
                RepoSession session = RepoSession.open(dir, runner);
                Fx.run(() -> {
                    for (RepoViewModel existing : repos) {
                        if (RepoPaths.same(existing.session().root(), session.root())) {
                            session.close();
                            selected.set(existing);
                            return;
                        }
                    }
                    RepoViewModel vm = new RepoViewModel(session, settings.get(), diffPreferences, errorHandler);
                    vm.setRepoOpener(this::openRepository);
                    repos.add(vm);
                    selected.set(vm);
                    settings.addRecent(session.root());
                });
            } catch (Exception e) {
                errorHandler.accept(e);
            }
        });
    }

    public String defaultCloneDir() {
        String d = settings.get().cloneDir;
        return d != null && !d.isBlank() ? d : System.getProperty("user.home");
    }

    /** Clones on a background thread, then opens the new repository. {@code onSuccess}/{@code onError} may run on any thread. */
    public void cloneRepository(String url, Path parent, String name,
                                java.util.function.Consumer<com.github.gitguiorg.gitgui.model.GitProgress> progress,
                                com.github.gitguiorg.gitgui.git.CancelToken cancel,
                                Consumer<Throwable> onError, Runnable onSuccess) {
        Thread.startVirtualThread(() -> {
            try {
                Path target = GitClient.cloneRepository(runner, url, parent, name, progress, cancel);
                settings.get().cloneDir = parent.toString();
                settings.save();
                Fx.run(() -> openRepository(target));
                onSuccess.run();
            } catch (Exception e) {
                onError.accept(e);
            }
        });
    }

    public void initRepository(Path dir) {
        Thread.startVirtualThread(() -> {
            try {
                GitClient.init(dir, runner);
                openRepository(dir);
            } catch (Exception e) {
                errorHandler.accept(e);
            }
        });
    }

    public void closeRepository(RepoViewModel vm) {
        repos.remove(vm);
        vm.close();
    }

    public void removeRecent(String path) {
        settings.removeRecent(path);
        Fx.run(recentChangedHandler);
    }

    public void shutdown() {
        for (RepoViewModel r : List.copyOf(repos)) r.close();
    }
}
