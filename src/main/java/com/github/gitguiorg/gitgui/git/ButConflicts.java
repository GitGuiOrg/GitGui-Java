package com.github.gitguiorg.gitgui.git;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** The conflicts of the oldest conflicted GitButler commit, from {@code but resolve conflicts --json}. */
public record ButConflicts(String commitId, String branch, List<FileConflict> files, int manual) {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record FileConflict(String path, List<Hunk> hunks) {
        /** First hunk of the file as two short lines, or an empty string when there is no hunk. */
        public String preview() {
            return hunks.isEmpty() ? "" : hunks.get(0).preview();
        }
    }

    public record Hunk(int line, String ours, String base, String theirs) {
        /** Both sides of this hunk as two short lines. */
        public String preview() {
            return "Remote: " + oneLine(ours) + "\nLocal: " + oneLine(theirs);
        }
    }

    public boolean isEmpty() { return files.isEmpty(); }

    public static ButConflicts parse(String json) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (IOException e) {
            throw new IllegalArgumentException("invalid but resolve conflicts JSON: " + e.getMessage(), e);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("invalid but resolve conflicts JSON: expected an object");
        }
        List<FileConflict> files = new ArrayList<>();
        JsonNode array = root.path("files");
        if (array.isArray()) {
            for (JsonNode file : array) files.add(file(file));
        }
        int manual = 0;
        JsonNode manualArray = root.path("manual");
        if (manualArray.isArray()) manual = manualArray.size();
        return new ButConflicts(text(root.path("commit_id")), text(root.path("branch")), files, manual);
    }

    private static FileConflict file(JsonNode node) {
        List<Hunk> hunks = new ArrayList<>();
        JsonNode array = node.path("hunks");
        if (array.isArray()) {
            for (JsonNode hunk : array) {
                hunks.add(new Hunk(hunk.path("line").asInt(0), text(hunk.path("ours")),
                        text(hunk.path("base")), text(hunk.path("theirs"))));
            }
        }
        return new FileConflict(text(node.path("path")), hunks);
    }

    private static String text(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? "" : node.asText("");
    }

    private static String oneLine(String text) {
        if (text == null) return "";
        String line = text.replace('\r', ' ').replace('\n', ' ').strip();
        return line.length() <= 100 ? line : line.substring(0, 99) + "…";
    }
}
