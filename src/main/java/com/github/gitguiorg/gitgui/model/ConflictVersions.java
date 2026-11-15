package com.github.gitguiorg.gitgui.model;

/** Content of the three index stages of a conflicted path; a side is null when that stage does not exist. */
public record ConflictVersions(byte[] base, byte[] ours, byte[] theirs) {
    public boolean isBinary() {
        return hasNul(base) || hasNul(ours) || hasNul(theirs);
    }

    private static boolean hasNul(byte[] b) {
        if (b == null) return false;
        for (byte x : b) if (x == 0) return true;
        return false;
    }
}
