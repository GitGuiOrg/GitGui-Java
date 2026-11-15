package com.github.gitguiorg.gitgui;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.gitguiorg.gitgui.git.GitRunner;
import com.github.gitguiorg.gitgui.service.RepoSession;
import com.github.gitguiorg.gitgui.service.SettingsService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServiceTest {
    @TempDir Path tmp;

    @Test
    void settingsRoundTripAndRecent() {
        SettingsService s = new SettingsService(tmp.resolve("cfg"));
        s.get().gitPath = "C:/git/git.exe";
        s.addRecent(tmp.resolve("a"));
        s.addRecent(tmp.resolve("b"));
        s.addRecent(tmp.resolve("a"));

        SettingsService reloaded = new SettingsService(tmp.resolve("cfg"));
        assertThat(reloaded.get().gitPath).isEqualTo("C:/git/git.exe");
        assertThat(reloaded.get().recentRepos).hasSize(2);
        assertThat(reloaded.get().recentRepos.get(0)).endsWith("a");
    }

    @Test
    void recentReposAreIdentifiedByRepositoryNotBySpelling() {
        SettingsService s = new SettingsService(tmp.resolve("cfg-recent"));
        Path repo = tmp.resolve("repo");
        s.addRecent(repo);
        s.addRecent(repo.resolve("."));       // the same folder written differently
        assertThat(s.get().recentRepos).hasSize(1);
        if (java.io.File.separatorChar == '\\') {
            s.addRecent(Path.of(repo.toString().toUpperCase(java.util.Locale.ROOT)));
            assertThat(s.get().recentRepos).hasSize(1);
        }

        assertThat(s.removeRecent(repo.resolve("sub/.."))).isTrue();
        assertThat(s.get().recentRepos).isEmpty();
        assertThat(s.removeRecent(repo)).isFalse();
    }

    @Test
    void unusablePathsInTheSettingsFileAreDropped() throws Exception {
        Path cfg = tmp.resolve("cfg-bad");
        Files.createDirectories(cfg);
        Files.writeString(cfg.resolve("settings.json"),
                "{\"recentRepos\":[\"C:/ok\",\"bad\\u0000path\"],"
                + "\"sessionRepos\":[\"bad\\u0000path\",\"\"]}");

        SettingsService s = new SettingsService(cfg);

        assertThat(s.get().recentRepos).containsExactly("C:/ok");
        assertThat(s.get().sessionRepos).isEmpty();
    }

    @Test
    void windowLayoutAndSessionArePersisted() {
        SettingsService s = new SettingsService(tmp.resolve("cfg2"));
        var c = s.get();
        c.windowWidth = 1111;
        c.windowHeight = 777;
        c.windowX = 20;
        c.windowY = 30;
        c.windowMaximized = true;
        c.sidebarDivider = 0.31;
        c.mainDivider = 0.42;
        c.detailsDivider = 0.53;
        c.restoreSession = false;
        c.sessionRepos = java.util.List.of("C:/a", "C:/b");
        c.sessionActive = "C:/b";
        s.save();

        var r = new SettingsService(tmp.resolve("cfg2")).get();
        assertThat(r.windowWidth).isEqualTo(1111);
        assertThat(r.windowHeight).isEqualTo(777);
        assertThat(r.windowX).isEqualTo(20);
        assertThat(r.windowY).isEqualTo(30);
        assertThat(r.windowMaximized).isTrue();
        assertThat(r.sidebarDivider).isEqualTo(0.31);
        assertThat(r.mainDivider).isEqualTo(0.42);
        assertThat(r.detailsDivider).isEqualTo(0.53);
        assertThat(r.restoreSession).isFalse();
        assertThat(r.sessionRepos).containsExactly("C:/a", "C:/b");
        assertThat(r.sessionActive).isEqualTo("C:/b");
        // A fresh profile has no remembered position, so the window is centred by the OS.
        var fresh = new SettingsService(tmp.resolve("cfg3")).get();
        assertThat(fresh.windowX).isNaN();
        assertThat(fresh.windowY).isNaN();
        assertThat(fresh.restoreSession).isTrue();
    }

    @Test
    void corruptSettingsFallBackToDefaults() throws Exception {
        Files.createDirectories(tmp.resolve("cfg"));
        Files.writeString(tmp.resolve("cfg/settings.json"), "{ not json");
        assertThat(new SettingsService(tmp.resolve("cfg")).get().logPageSize).isEqualTo(500);
    }

    @Test
    void nullListsAndInvalidLimitsAreRepairedOnLoad() throws Exception {
        Path cfg = tmp.resolve("cfg-broken");
        Files.createDirectories(cfg);
        Files.writeString(cfg.resolve("settings.json"), """
                {"recentRepos": null, "sessionRepos": null, "maxRecent": -1,
                 "logPageSize": 0, "diffContextLines": -2, "diffMaxLines": 1, "autoFetchMinutes": -5,
                 "pullMode": null}
                """);

        SettingsService s = new SettingsService(cfg);
        assertThat(s.get().recentRepos).isEmpty();
        assertThat(s.get().sessionRepos).isEmpty();
        assertThat(s.get().maxRecent).isEqualTo(20);
        assertThat(s.get().logPageSize).isEqualTo(500);
        assertThat(s.get().diffContextLines).isEqualTo(3);
        assertThat(s.get().diffMaxLines).isEqualTo(20_000);
        assertThat(s.get().autoFetchMinutes).isZero();
        assertThat(s.get().pullMode).isEqualTo("FF_ONLY");

        s.addRecent(tmp.resolve("a"));
        assertThat(s.get().recentRepos).hasSize(1);
    }

    @Test
    void sessionFiresDebouncedChangeOnFileEditAndGitOperation() throws Exception {
        Path repo = tmp.resolve("repo");
        GitRunner runner = new GitRunner(null);
        com.github.gitguiorg.gitgui.git.GitClient.init(repo, runner);
        try (RepoSession session = RepoSession.open(repo, runner)) {
            CountDownLatch first = new CountDownLatch(1);
            session.onChanged(first::countDown);

            Files.writeString(repo.resolve("x.txt"), "hi\n");
            assertThat(first.await(10, TimeUnit.SECONDS)).as("file change").isTrue();

            CountDownLatch second = new CountDownLatch(1);
            session.onChanged(second::countDown);
            session.mutate(() -> session.git().stage(java.util.List.of("x.txt"))).get(10, TimeUnit.SECONDS);
            assertThat(second.await(10, TimeUnit.SECONDS)).as("git change").isTrue();
            assertThat(session.async(() -> session.git().status()).get(10, TimeUnit.SECONDS).entries()).hasSize(1);
        }
    }
}