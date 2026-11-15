package com.github.gitguiorg.gitgui.model;

import java.util.List;

/** Diff of a single file. {@code headerLines} are everything before the first hunk (diff --git, index, ---, +++). */
public record FileDiff(List<String> headerLines, String oldPath, String newPath, boolean binary,
                       boolean newFile, boolean deletedFile, List<Hunk> hunks) {
    public String path() {
        return newPath == null || "/dev/null".equals(newPath) ? oldPath : newPath;
    }

    /** True when the diff shows the text of a Git LFS pointer instead of the real (stored elsewhere) content. */
    public boolean lfsPointer() {
        if (binary || hunks.isEmpty()) return false;
        boolean version = false;
        boolean oid = false;
        for (Hunk h : hunks) {
            for (DiffLine l : h.lines()) {
                if (l.type() != DiffLine.Type.ADDED && l.type() != DiffLine.Type.CONTEXT) continue;
                String t = l.text().strip();
                if (t.startsWith("version https://git-lfs.github.com/spec/")) version = true;
                if (t.startsWith("oid sha256:")) oid = true;
            }
        }
        return version && oid;
    }
}
