package com.github.gitguiorg.gitgui.git;

import com.github.gitguiorg.gitgui.model.Commit;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Parses output of {@code git log --format=FORMAT}. Records start with RS (0x1e), fields are NUL separated. */
public final class LogParser {
    /** The last field is the signature check from {@code %G?} (N = unsigned). */
    public static final String FORMAT = "%x1e%H%x00%P%x00%an%x00%ae%x00%at%x00%D%x00%s%x00%b%x00%G?";

    private LogParser() {}

    public static List<Commit> parse(String out) {
        List<Commit> result = new ArrayList<>();
        for (String record : out.split("\u001e")) {
            if (record.isBlank()) continue;
            String[] f = record.split("\0", -1);
            if (f.length < 9) continue;
            List<String> parents = f[1].isBlank() ? List.of() : List.of(f[1].trim().split(" "));
            List<String> refs = f[5].isBlank() ? List.of() : Arrays.stream(f[5].split(", ")).map(String::trim).toList();
            result.add(new Commit(f[0].strip(), parents, f[2], f[3],
                    Instant.ofEpochSecond(Long.parseLong(f[4])), refs, f[6], f[7].strip(), f[8].strip()));
        }
        return result;
    }
}
