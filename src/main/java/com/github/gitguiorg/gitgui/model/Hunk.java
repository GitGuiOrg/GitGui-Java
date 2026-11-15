package com.github.gitguiorg.gitgui.model;

import java.util.List;

public record Hunk(int oldStart, int oldCount, int newStart, int newCount, String section, List<DiffLine> lines) {
    public String header() {
        return "@@ -" + oldStart + "," + oldCount + " +" + newStart + "," + newCount + " @@" + section;
    }
}
