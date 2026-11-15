package com.github.gitguiorg.gitgui.git;

import com.github.gitguiorg.gitgui.model.RepoStatus;
import com.github.gitguiorg.gitgui.model.StatusEntry;
import com.github.gitguiorg.gitgui.model.StatusEntry.Kind;
import java.util.ArrayList;
import java.util.List;

/** Parses {@code git status --porcelain=v2 --branch -z}. */
public final class StatusParser {
    private StatusParser() {}

    public static RepoStatus parse(String out) {
        String branch = "";
        String oid = "";
        String upstream = null;
        int ahead = 0;
        int behind = 0;
        List<StatusEntry> entries = new ArrayList<>();

        String[] tok = out.split("\0");
        for (int i = 0; i < tok.length; i++) {
            String t = tok[i];
            if (t.isEmpty()) continue;
            if (t.startsWith("# branch.oid ")) {
                oid = t.substring(13);
            } else if (t.startsWith("# branch.head ")) {
                branch = t.substring(14);
            } else if (t.startsWith("# branch.upstream ")) {
                upstream = t.substring(18);
            } else if (t.startsWith("# branch.ab ")) {
                String[] ab = t.substring(12).split(" ");
                ahead = Integer.parseInt(ab[0].substring(1));
                behind = Integer.parseInt(ab[1].substring(1));
            } else if (t.startsWith("1 ")) {
                String[] f = t.split(" ", 9);
                entries.add(new StatusEntry(Kind.ORDINARY, f[1].charAt(0), f[1].charAt(1), f[8], null));
            } else if (t.startsWith("2 ")) {
                String[] f = t.split(" ", 10);
                String orig = i + 1 < tok.length ? tok[++i] : null;
                entries.add(new StatusEntry(Kind.RENAMED, f[1].charAt(0), f[1].charAt(1), f[9], orig));
            } else if (t.startsWith("u ")) {
                String[] f = t.split(" ", 11);
                entries.add(new StatusEntry(Kind.UNMERGED, f[1].charAt(0), f[1].charAt(1), f[10], null));
            } else if (t.startsWith("? ")) {
                entries.add(new StatusEntry(Kind.UNTRACKED, '?', '?', t.substring(2), null));
            } else if (t.startsWith("! ")) {
                entries.add(new StatusEntry(Kind.IGNORED, '!', '!', t.substring(2), null));
            }
        }
        return new RepoStatus(branch, oid, upstream, ahead, behind, entries);
    }
}
