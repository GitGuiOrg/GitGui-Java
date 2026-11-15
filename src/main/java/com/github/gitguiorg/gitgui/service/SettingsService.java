package com.github.gitguiorg.gitgui.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.github.gitguiorg.gitgui.util.RepoPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Loads/saves {@link Settings} as JSON. Saves are atomic (temp file + move). */
public final class SettingsService {
    private static final Logger LOG = LoggerFactory.getLogger(SettingsService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private final Path file;
    private Settings settings;

    public SettingsService(Path configDir) {
        this.file = configDir.resolve("settings.json");
        this.settings = load();
    }

    public synchronized Settings get() {
        return settings;
    }

    private Settings load() {
        if (!Files.isRegularFile(file)) return new Settings();
        try {
            return sanitize(MAPPER.readValue(file.toFile(), Settings.class));
        } catch (IOException e) {
            LOG.warn("Cannot read {}, using defaults: {}", file, e.getMessage());
            return new Settings();
        }
    }

    /** Repairs values a hand-edited settings file can leave null or out of range. */
    private static Settings sanitize(Settings s) {
        if (s == null) return new Settings();
        Settings defaults = new Settings();
        if (s.recentRepos == null) s.recentRepos = new ArrayList<>();
        else s.recentRepos.removeIf(p -> !validPath(p));
        if (s.sessionRepos == null) s.sessionRepos = new ArrayList<>();
        else s.sessionRepos.removeIf(p -> !validPath(p));
        if (s.maxRecent < 1) s.maxRecent = defaults.maxRecent;
        if (s.logPageSize < 1) s.logPageSize = defaults.logPageSize;
        if (s.diffContextLines < 0) s.diffContextLines = defaults.diffContextLines;
        if (s.diffMaxLines < 100) s.diffMaxLines = defaults.diffMaxLines;
        if (s.autoFetchMinutes < 0) s.autoFetchMinutes = 0;
        if (!"MERGE".equals(s.pullMode) && !"REBASE".equals(s.pullMode) && !"FF_ONLY".equals(s.pullMode)) {
            s.pullMode = defaults.pullMode;
        }
        return s;
    }

    /** A stored folder is usable only when it can be turned into a Path; a bad string would crash the startup. */
    private static boolean validPath(String p) {
        if (p == null || p.isBlank()) return false;
        try {
            Path.of(p);
            return true;
        } catch (java.nio.file.InvalidPathException e) {
            return false;
        }
    }

    public synchronized void save() {
        try {
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            Path tmp = file.resolveSibling("settings.json.tmp");
            MAPPER.writeValue(tmp.toFile(), settings);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOG.error("Cannot save {}", file, e);
        }
    }

    /** Moves the repo to the top of the recent list and persists, folding Windows case variants. */
    public synchronized void addRecent(Path repo) {
        String p = repo.toAbsolutePath().normalize().toString();
        String key = RepoPaths.key(repo);
        settings.recentRepos.removeIf(entry -> key.equals(RepoPaths.keyOrNull(entry)));
        settings.recentRepos.add(0, p);
        while (settings.recentRepos.size() > settings.maxRecent) {
            settings.recentRepos.remove(settings.recentRepos.size() - 1);
        }
        save();
    }

    /** Drops the given repository and any entry pointing at it; true when an entry was removed. */
    public synchronized boolean removeRecent(Path repo) {
        String key = RepoPaths.key(repo);
        boolean removed = settings.recentRepos.removeIf(entry -> key.equals(RepoPaths.keyOrNull(entry)));
        if (removed) save();
        return removed;
    }

    public synchronized void removeRecent(String path) {
        String key = RepoPaths.keyOrNull(path);
        if (key == null) settings.recentRepos.remove(path);
        else settings.recentRepos.removeIf(entry -> key.equals(RepoPaths.keyOrNull(entry)));
        save();
    }
}
