package com.github.gitguiorg.gitgui.git;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * The parts of {@code but --json status} the GitButler tab displays. Unknown fields are ignored so a
 * newer but version can still be read; if the overall shape is not JSON the caller falls back to raw text.
 */
public record ButStatus(List<Change> uncommittedChanges, List<Stack> stacks, Commit base, int behind) {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Number of commits currently marked as conflicted by but status. */
    public int conflictedCommits() {
        return stacks.stream().flatMap(stack -> stack.branches().stream())
                .flatMap(branch -> branch.commits().stream())
                .filter(Commit::conflicted)
                .mapToInt(commit -> 1)
                .sum();
    }

    public record Change(String cliId, String path, String changeType) {}

    public record Stack(String cliId, List<Branch> branches) {}

    public record Branch(String cliId, String name, List<Commit> commits,
                         List<Commit> upstreamCommits, String status) {}

    public record Commit(String cliId, String id, Instant createdAt, String message,
                         String authorName, boolean conflicted) {
        /** First line of the message, without a trailing newline. */
        public String subject() {
            if (message == null) return "";
            int newline = message.indexOf('\n');
            return (newline < 0 ? message : message.substring(0, newline)).strip();
        }

        /** Seven-character id, or the whole id when it is shorter. */
        public String shortId() {
            if (id == null) return "";
            return id.substring(0, Math.min(7, id.length()));
        }
    }

    /** Parses the JSON emitted by {@code but --json status}. */
    public static ButStatus parse(String json) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (IOException e) {
            throw new IllegalArgumentException("invalid but status JSON: " + e.getMessage(), e);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("invalid but status JSON: expected an object");
        }
        return new ButStatus(changes(root.path("uncommittedChanges")), stacks(root.path("stacks")),
                commit(root.path("mergeBase")), root.path("upstreamState").path("behind").asInt(0));
    }

    private static List<Change> changes(JsonNode array) {
        List<Change> out = new ArrayList<>();
        if (!array.isArray()) return List.of();
        for (JsonNode n : array) {
            out.add(new Change(text(n, "cliId"), text(n, "filePath"), text(n, "changeType")));
        }
        return List.copyOf(out);
    }

    private static List<Stack> stacks(JsonNode array) {
        List<Stack> out = new ArrayList<>();
        if (!array.isArray()) return List.of();
        for (JsonNode n : array) {
            List<Branch> branches = new ArrayList<>();
            for (JsonNode b : n.path("branches")) {
                branches.add(new Branch(text(b, "cliId"), text(b, "name"), commits(b.path("commits")),
                        commits(b.path("upstreamCommits")), text(b, "branchStatus")));
            }
            out.add(new Stack(text(n, "cliId"), List.copyOf(branches)));
        }
        return List.copyOf(out);
    }

    private static List<Commit> commits(JsonNode array) {
        List<Commit> out = new ArrayList<>();
        if (!array.isArray()) return List.of();
        for (JsonNode n : array) out.add(commit(n));
        return List.copyOf(out);
    }

    private static Commit commit(JsonNode n) {
        if (n == null || !n.isObject()) return null;
        return new Commit(text(n, "cliId"), text(n, "commitId"), instant(n, "createdAt"),
                text(n, "message"), text(n, "authorName"), n.path("conflicted").asBoolean(false));
    }

    private static Instant instant(JsonNode n, String field) {
        String value = text(n, field);
        if (value.isBlank()) return null;
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode value = n.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("");
    }
}
