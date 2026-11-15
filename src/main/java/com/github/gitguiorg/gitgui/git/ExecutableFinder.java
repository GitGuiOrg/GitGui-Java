package com.github.gitguiorg.gitgui.git;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Finds external CLI executables in an explicit path, environment variables, PATH or known locations. */
final class ExecutableFinder {
    private ExecutableFinder() {
    }

    /**
     * @param override explicit path, or null/blank to search the other locations
     * @param pathEnv  value of PATH; a parameter so the search can be tested without touching the environment
     * @param names    executable names to try in each PATH entry, e.g. {@code git.exe}, {@code git}
     * @param envVars  environment variables with an explicit path, checked after {@code override}
     * @param fallbacks known install locations, checked last
     * @return the executable path, or null when nothing was found
     */
    static String find(String override, String pathEnv, List<String> names, List<String> envVars,
                       List<Path> fallbacks) {
        if (override != null && !override.isBlank()) return regular(unquoted(override));
        for (String env : envVars) {
            String value = System.getenv(env);
            if (value == null || value.isBlank()) continue;
            String found = regular(unquoted(value));
            if (found != null) return found;
        }
        if (pathEnv != null) {
            for (String entry : pathEnv.split(File.pathSeparator)) {
                String dir = unquoted(entry);
                if (dir.isEmpty()) continue;
                for (String name : names) {
                    try {
                        String found = regular(Path.of(dir, name));
                        if (found != null) return found;
                    } catch (RuntimeException ignored) {
                        // invalid PATH entry
                    }
                }
            }
        }
        for (Path candidate : fallbacks) {
            String found = regular(candidate);
            if (found != null) return found;
        }
        return null;
    }

    private static String regular(Path path) {
        return regular(path.toString());
    }

    private static String regular(String path) {
        try {
            Path p = Path.of(path);
            return Files.isRegularFile(p) ? p.toAbsolutePath().normalize().toString() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Removes surrounding whitespace and quotes from a PATH entry or a configured path. */
    static String unquoted(String value) {
        String s = value.strip();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length() - 1).strip();
        return s;
    }
}
