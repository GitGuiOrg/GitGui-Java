package com.github.gitguiorg.gitgui.git;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The parts of {@code but pull --check --json} needed before updating the workspace. */
public record ButPullCheck(String baseBranch, int upstreamCommits, List<BranchStatus> branches,
                           boolean upToDate, boolean hasWorktreeConflicts) {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record BranchStatus(String name, String status, boolean rebasable) {}

    /** True when the check found anything that would need conflict resolution. */
    public boolean hasConflicts() {
        if (hasWorktreeConflicts) return true;
        return branches.stream().anyMatch(branch -> branch.status() != null
                && branch.status().toLowerCase(Locale.ROOT).contains("conflict"));
    }

    /** Human-readable preflight result for the pull confirmation dialog. */
    public String summary() {
        StringBuilder text = new StringBuilder();
        if (baseBranch != null && !baseBranch.isBlank()) text.append("Base branch: ").append(baseBranch);
        if (upstreamCommits > 0) {
            if (!text.isEmpty()) text.append('\n');
            text.append("Upstream: ").append(upstreamCommits)
                    .append(upstreamCommits == 1 ? " new commit" : " new commits");
        }
        for (BranchStatus branch : branches) {
            if (!text.isEmpty()) text.append('\n');
            text.append("• ").append(branch.name() == null || branch.name().isBlank()
                    ? "(unnamed branch)" : branch.name());
            if (branch.status() != null && !branch.status().isBlank()) {
                text.append(": ").append(branch.status());
            }
            if (branch.rebasable()) text.append(" (rebasable)");
        }
        if (hasWorktreeConflicts) {
            if (!text.isEmpty()) text.append('\n');
            text.append("Worktrees with conflicts are present.");
        }
        if (text.isEmpty()) {
            text.append(upToDate ? "Already up to date." : "No branch details returned.");
        }
        return text.toString();
    }

    /** Parses the JSON emitted by {@code but pull --check --json}. */
    public static ButPullCheck parse(String json) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (IOException e) {
            throw new IllegalArgumentException("invalid but pull --check JSON: " + e.getMessage(), e);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("invalid but pull --check JSON: expected an object");
        }
        JsonNode base = root.path("baseBranch");
        JsonNode upstream = root.path("upstreamCommits");
        return new ButPullCheck(
                text(base.path("name")),
                upstream.path("count").asInt(0),
                branches(root.path("branchStatuses")),
                root.path("upToDate").asBoolean(false),
                root.path("hasWorktreeConflicts").asBoolean(false));
    }

    private static List<BranchStatus> branches(JsonNode array) {
        List<BranchStatus> out = new ArrayList<>();
        if (!array.isArray()) return out;
        for (JsonNode node : array) {
            out.add(new BranchStatus(text(node.path("name")), text(node.path("status")),
                    node.path("rebasable").asBoolean(false)));
        }
        return out;
    }

    private static String text(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? "" : node.asText("");
    }
}
