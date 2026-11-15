package com.github.gitguiorg.gitgui.git;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Parses the unapplied branches from {@code but branch list --json}. */
final class ButBranches {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ButBranches() {
    }

    static List<ButBranch> parse(String json) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (IOException e) {
            throw new IllegalArgumentException("invalid but branch JSON: " + e.getMessage(), e);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("invalid but branch JSON: expected an object");
        }
        JsonNode array = root.path("branches");
        if (!array.isArray()) return List.of();
        List<ButBranch> out = new ArrayList<>();
        for (JsonNode node : array) {
            out.add(new ButBranch(text(node, "name"), node.path("hasLocal").asBoolean(false),
                    commitsAhead(node), instant(node), author(node), node.path("mergesCleanly").asBoolean(true)));
        }
        return List.copyOf(out);
    }

    private static Integer commitsAhead(JsonNode node) {
        JsonNode value = node.path("commitsAhead");
        return value.isMissingNode() || value.isNull() ? null : value.asInt();
    }

    private static Instant instant(JsonNode node) {
        JsonNode value = node.path("lastCommitAt");
        return value.isNumber() && value.asLong() > 0 ? Instant.ofEpochMilli(value.asLong()) : null;
    }

    private static String author(JsonNode node) {
        return text(node.path("lastAuthor"), "name");
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("");
    }
}
