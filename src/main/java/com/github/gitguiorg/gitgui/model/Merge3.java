package com.github.gitguiorg.gitgui.model;

import com.github.difflib.DiffUtils;
import com.github.difflib.patch.AbstractDelta;
import java.util.ArrayList;
import java.util.List;

/**
 * Three-way line merge (diff3). Changes on either side are grouped into regions of the base; a region touched by
 * only one side, or by both with identical results, merges cleanly, otherwise it becomes a conflict.
 */
public final class Merge3 {
    /**
     * A region. {@code base/ours/theirs} are the lines each version has for this region. Positions are 0-based
     * line indexes where the region starts in each file.
     */
    public record Chunk(boolean conflict, List<String> base, List<String> ours, List<String> theirs,
                        int baseStart, int oursStart, int theirsStart) {
        /** Merged lines of a clean chunk. */
        public List<String> merged() {
            return ours.equals(base) ? theirs : ours;
        }
    }

    private Merge3() {}

    public static List<Chunk> merge(List<String> base, List<String> ours, List<String> theirs) {
        List<Region> o = regions(base, ours);
        List<Region> t = regions(base, theirs);
        List<Chunk> chunks = new ArrayList<>();
        int i = 0, a = 0, b = 0, oursPos = 0, theirsPos = 0;

        while (a < o.size() || b < t.size()) {
            int gs = Integer.MAX_VALUE;
            if (a < o.size()) gs = Math.min(gs, o.get(a).start);
            if (b < t.size()) gs = Math.min(gs, t.get(b).start);
            if (gs > i) {
                List<String> same = base.subList(i, gs);
                chunks.add(new Chunk(false, same, same, same, i, oursPos, theirsPos));
                oursPos += same.size();
                theirsPos += same.size();
            }
            int ge = gs;
            List<Region> go = new ArrayList<>();
            List<Region> gt = new ArrayList<>();
            boolean added;
            do {
                added = false;
                while (a < o.size() && o.get(a).start <= ge) {
                    go.add(o.get(a));
                    ge = Math.max(ge, o.get(a).end());
                    a++;
                    added = true;
                }
                while (b < t.size() && t.get(b).start <= ge) {
                    gt.add(t.get(b));
                    ge = Math.max(ge, t.get(b).end());
                    b++;
                    added = true;
                }
            } while (added);

            List<String> baseLines = List.copyOf(base.subList(gs, ge));
            List<String> oursLines = apply(base, go, gs, ge);
            List<String> theirsLines = apply(base, gt, gs, ge);
            boolean conflict = !go.isEmpty() && !gt.isEmpty() && !oursLines.equals(theirsLines);
            chunks.add(new Chunk(conflict, baseLines, oursLines, theirsLines, gs, oursPos, theirsPos));
            oursPos += oursLines.size();
            theirsPos += theirsLines.size();
            i = ge;
        }
        if (i < base.size()) {
            List<String> same = base.subList(i, base.size());
            chunks.add(new Chunk(false, same, same, same, i, oursPos, theirsPos));
        }
        return chunks;
    }

    /** Text with diff3-style conflict markers for unresolved chunks. */
    public static List<String> toMarkedLines(List<Chunk> chunks) {
        List<String> out = new ArrayList<>();
        for (Chunk c : chunks) {
            if (!c.conflict()) {
                out.addAll(c.merged());
            } else {
                out.add(ConflictMarkers.OURS);
                out.addAll(c.ours());
                out.add(ConflictMarkers.BASE);
                out.addAll(c.base());
                out.add(ConflictMarkers.SEP);
                out.addAll(c.theirs());
                out.add(ConflictMarkers.THEIRS);
            }
        }
        return out;
    }

    // ------------------------------------------------------------ text helpers

    public static List<String> lines(String text) {
        if (text == null || text.isEmpty()) return new ArrayList<>();
        List<String> l = new ArrayList<>(List.of(text.split("\r?\n", -1)));
        if (l.get(l.size() - 1).isEmpty()) l.remove(l.size() - 1);
        return l;
    }

    public static String eolOf(String text) {
        return text != null && text.contains("\r\n") ? "\r\n" : "\n";
    }

    // ------------------------------------------------------------ internals

    private record Region(int start, int size, List<String> replacement) {
        int end() { return start + size; }
    }

    private static List<Region> regions(List<String> base, List<String> side) {
        List<Region> r = new ArrayList<>();
        for (AbstractDelta<String> d : DiffUtils.diff(base, side).getDeltas()) {
            r.add(new Region(d.getSource().getPosition(), d.getSource().size(), List.copyOf(d.getTarget().getLines())));
        }
        return r;
    }

    private static List<String> apply(List<String> base, List<Region> regions, int gs, int ge) {
        List<String> out = new ArrayList<>();
        int pos = gs;
        for (Region r : regions) {
            out.addAll(base.subList(pos, r.start));
            out.addAll(r.replacement);
            pos = r.end();
        }
        out.addAll(base.subList(pos, ge));
        return out;
    }
}
