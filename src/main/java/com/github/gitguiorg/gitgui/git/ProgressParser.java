package com.github.gitguiorg.gitgui.git;

import com.github.gitguiorg.gitgui.model.GitProgress;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses lines like "Receiving objects:  45% (45/100), 1.2 MiB | 2 MiB/s". */
public final class ProgressParser {
    private static final Pattern P = Pattern.compile("^(?:remote: )?([A-Za-z][A-Za-z ]*?):\\s+(\\d+)%");

    private ProgressParser() {}

    public static Optional<GitProgress> parse(String line) {
        Matcher m = P.matcher(line);
        if (!m.find()) return Optional.empty();
        return Optional.of(new GitProgress(m.group(1), Integer.parseInt(m.group(2))));
    }
}