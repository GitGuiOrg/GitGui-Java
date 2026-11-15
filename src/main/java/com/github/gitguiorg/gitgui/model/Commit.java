package com.github.gitguiorg.gitgui.model;

import com.github.gitguiorg.gitgui.util.Hashes;
import java.time.Instant;
import java.util.List;

/**
 * @param signature result of {@code %G?}: G good, B bad, U unknown validity, X/Y expired, R revoked,
 *                  E missing key, N unsigned
 */
public record Commit(String hash, List<String> parents, String authorName, String authorEmail,
                     Instant authorDate, List<String> refs, String subject, String body, String signature) {
    public String shortHash() {
        return Hashes.shortHash(hash);
    }

    public boolean isMerge() {
        return parents.size() > 1;
    }

    public boolean signed() {
        return signature != null && !signature.isBlank() && !signature.equals("N");
    }

    /** Human readable signature state for the commit details. */
    public String signatureLabel() {
        if (!signed()) return "Not signed";
        return switch (signature) {
            case "G" -> "Signed: good signature";
            case "B" -> "Signed: BAD signature";
            case "U" -> "Signed: good signature, unknown validity";
            case "X" -> "Signed: good signature, expired";
            case "Y" -> "Signed: good signature from an expired key";
            case "R" -> "Signed: good signature from a revoked key";
            case "E" -> "Signed: cannot check, key missing";
            default -> "Signed: " + signature;
        };
    }
}
