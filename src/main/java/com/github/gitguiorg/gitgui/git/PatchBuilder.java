package com.github.gitguiorg.gitgui.git;

import com.github.gitguiorg.gitgui.model.DiffLine;
import com.github.gitguiorg.gitgui.model.FileDiff;
import com.github.gitguiorg.gitgui.model.Hunk;
import java.util.Optional;
import java.util.Set;

/**
 * Builds partial patches (one hunk, optionally a subset of its lines) for {@code git apply --cached}.
 * <p>
 * Forward mode (stage / discard-reverse): unselected removals become context, unselected additions are dropped.
 * Reverse mode (unstage, patch is applied with {@code --reverse}): unselected additions become context,
 * unselected removals are dropped.
 */
public final class PatchBuilder {
    private PatchBuilder() {}

    /**
     * Patch of a whole file, limited to the given hunks (all of them when {@code hunks} is null).
     * The file header appears once, so the result can be saved as a .patch and applied with git apply.
     *
     * @param hunks hunk index → selected line indexes (null value = whole hunk)
     */
    public static Optional<String> buildFile(FileDiff file, java.util.Map<Integer, Set<Integer>> hunks) {
        String header = String.join("\n", file.headerLines()) + "\n";
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < file.hunks().size(); i++) {
            if (hunks != null && !hunks.containsKey(i)) continue;
            Optional<String> part = build(file, i, hunks == null ? null : hunks.get(i), false);
            if (part.isEmpty()) continue;
            String text = part.get();
            body.append(text.startsWith(header) ? text.substring(header.length()) : text);
        }
        return body.isEmpty() ? Optional.empty() : Optional.of(header + body);
    }

    /** @param selectedLines indexes into {@code hunk.lines()}, or null to select the whole hunk */
    public static Optional<String> build(FileDiff file, int hunkIndex, Set<Integer> selectedLines, boolean reverse) {
        Hunk hunk = file.hunks().get(hunkIndex);
        StringBuilder body = new StringBuilder();
        int oldCount = 0;
        int newCount = 0;
        boolean changed = false;
        boolean prevKept = false;

        for (int i = 0; i < hunk.lines().size(); i++) {
            DiffLine l = hunk.lines().get(i);
            boolean sel = selectedLines == null || selectedLines.contains(i);
            Character out = null;
            switch (l.type()) {
                case CONTEXT -> out = ' ';
                case ADDED -> out = sel ? Character.valueOf('+') : (reverse ? Character.valueOf(' ') : null);
                case REMOVED -> out = sel ? Character.valueOf('-') : (reverse ? null : Character.valueOf(' '));
                case NO_NEWLINE -> out = prevKept ? Character.valueOf('\\') : null;
            }
            prevKept = out != null;
            if (out == null) continue;
            body.append(out).append(l.text()).append('\n');
            switch (out) {
                case ' ' -> { oldCount++; newCount++; }
                case '+' -> { newCount++; changed = true; }
                case '-' -> { oldCount++; changed = true; }
                default -> { }
            }
        }
        if (!changed) return Optional.empty();

        StringBuilder patch = new StringBuilder();
        for (String h : file.headerLines()) patch.append(h).append('\n');
        patch.append("@@ -").append(hunk.oldStart()).append(',').append(oldCount)
                .append(" +").append(hunk.newStart()).append(',').append(newCount)
                .append(" @@").append(hunk.section()).append('\n');
        patch.append(body);
        return Optional.of(patch.toString());
    }
}