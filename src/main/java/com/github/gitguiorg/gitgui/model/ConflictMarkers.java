package com.github.gitguiorg.gitgui.model;

import java.util.ArrayList;
import java.util.List;    /** Finds and resolves diff3-style conflict blocks inside a list of lines. */
public final class ConflictMarkers {
    public static final String OURS = "<<<<<<< ours";
    public static final String BASE = "||||||| base";
    public static final String SEP = "=======";
    public static final String THEIRS = ">>>>>>> theirs";

    public enum Choice { OURS, THEIRS, BASE, OURS_THEN_THEIRS, THEIRS_THEN_OURS }

    /** Lines {@code startLine..endLine} (inclusive) are the whole block, markers included. */
    public record Block(int startLine, int endLine, List<String> ours, List<String> base, List<String> theirs) {}

    private ConflictMarkers() {}

    public static List<Block> find(List<String> lines) {
        List<Block> blocks = new ArrayList<>();
        int state = 0;
        int start = -1;
        List<String> ours = new ArrayList<>(), base = new ArrayList<>(), theirs = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            switch (state) {
                case 0 -> {
                    if (l.startsWith("<<<<<<<")) {
                        state = 1;
                        start = i;
                        ours = new ArrayList<>();
                        base = new ArrayList<>();
                        theirs = new ArrayList<>();
                    }
                }
                case 1 -> {
                    if (l.startsWith("|||||||")) state = 2;
                    else if (l.equals(SEP)) state = 3;
                    else ours.add(l);
                }
                case 2 -> {
                    if (l.equals(SEP)) state = 3;
                    else base.add(l);
                }
                default -> {
                    if (l.startsWith(">>>>>>>")) {
                        blocks.add(new Block(start, i, ours, base, theirs));
                        state = 0;
                    } else {
                        theirs.add(l);
                    }
                }
            }
        }
        return blocks;
    }

    /**
     * True when a line still opens or closes a conflict block, also when the block is malformed or unclosed.
     * {@link #find(List)} only reports complete blocks, so this is what keeps an edited-but-unresolved file
     * from being marked resolved.
     */
    public static boolean hasMarkers(List<String> lines) {
        for (String l : lines) {
            if (l.startsWith("<<<<<<<") || l.startsWith("|||||||") || l.startsWith(">>>>>>>")) return true;
        }
        return false;
    }

    /** Returns a copy of {@code lines} with the block replaced by the chosen side(s). */
    public static List<String> resolve(List<String> lines, Block b, Choice c) {
        List<String> out = new ArrayList<>(lines.subList(0, b.startLine()));
        switch (c) {
            case OURS -> out.addAll(b.ours());
            case THEIRS -> out.addAll(b.theirs());
            case BASE -> out.addAll(b.base());
            case OURS_THEN_THEIRS -> { out.addAll(b.ours()); out.addAll(b.theirs()); }
            case THEIRS_THEN_OURS -> { out.addAll(b.theirs()); out.addAll(b.ours()); }
        }
        out.addAll(lines.subList(b.endLine() + 1, lines.size()));
        return out;
    }
}
