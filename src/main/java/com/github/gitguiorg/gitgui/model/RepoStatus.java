package com.github.gitguiorg.gitgui.model;

import java.util.List;

public record RepoStatus(String branch, String headOid, String upstream, int ahead, int behind,
                         List<StatusEntry> entries) {
    public boolean isDetached() {
        return "(detached)".equals(branch);
    }

    public boolean hasConflicts() {
        return entries.stream().anyMatch(StatusEntry::isConflicted);
    }
}
