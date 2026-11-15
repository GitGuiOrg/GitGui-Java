package com.github.gitguiorg.gitgui.util;

/** Display form of git object ids. */
public final class Hashes {
    public static final int SHORT_LENGTH = 7;

    private Hashes() {}

    /** First 7 characters of {@code hash}; null becomes empty and shorter strings come back unchanged. */
    public static String shortHash(String hash) {
        if (hash == null) return "";
        return hash.length() > SHORT_LENGTH ? hash.substring(0, SHORT_LENGTH) : hash;
    }

    /**
     * True for a hexadecimal object id, abbreviated or full: 40 characters in a SHA-1 repository, 64 in a SHA-256 one.
     * Anything else (an empty headline, {@code (initial)}, a branch name) is not an id.
     */
    public static boolean isObjectId(String hash) {
        if (hash == null || hash.length() < SHORT_LENGTH || hash.length() > 64) return false;
        for (int i = 0; i < hash.length(); i++) {
            char c = hash.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) return false;
        }
        return true;
    }
}
