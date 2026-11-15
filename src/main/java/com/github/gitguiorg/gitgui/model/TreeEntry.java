package com.github.gitguiorg.gitgui.model;

/** One entry of {@code git ls-tree}: a file, a folder or a submodule of a commit's tree. */
public record TreeEntry(String path, String name, String mode, String type, long size) {
    public boolean directory() {
        return "tree".equals(type);
    }

    /** A gitlink: the folder is a submodule checked out at the recorded commit. */
    public boolean submodule() {
        return "commit".equals(type);
    }

    public boolean symlink() {
        return "120000".equals(mode);
    }
}
