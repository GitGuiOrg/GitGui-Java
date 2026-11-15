package com.github.gitguiorg.gitgui.git;

import com.github.gitguiorg.gitgui.model.ReflogEntry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Parses output of {@code git log -g --format=FORMAT}. Records start with RS (0x1e), fields are NUL separated. */
public final class ReflogParser {
    public static final String FORMAT = "%x1e%H%x00%h%x00%gd%x00%gs%x00%aI";

    private ReflogParser() {}

    public static List<ReflogEntry> parse(String out) {
        List<ReflogEntry> result = new ArrayList<>();
        for (String record : out.split("\u001e")) {
            if (record.isBlank()) continue;
            String[] f = record.split("\0", -1);
            if (f.length < 5) continue;
            String action = f[3].strip();
            String message = "";
            int colon = action.indexOf(": ");
            if (colon > 0) {
                message = action.substring(colon + 2).strip();
                action = action.substring(0, colon);
            }
            result.add(new ReflogEntry(f[0].strip(), f[1].strip(), f[2].strip(), action, message, date(f[4])));
        }
        return result;
    }

    private static Instant date(String text) {
        try {
            return Instant.parse(text.strip());
        } catch (RuntimeException e) {
            return null;
        }
    }
}
