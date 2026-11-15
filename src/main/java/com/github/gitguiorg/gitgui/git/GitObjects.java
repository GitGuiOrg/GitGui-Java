package com.github.gitguiorg.gitgui.git;

import com.github.gitguiorg.gitgui.model.FileContent;
import com.github.gitguiorg.gitgui.model.TreeEntry;
import com.github.gitguiorg.gitgui.util.TextEncoding;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Tree and blob reads of a {@link GitClient}. */
final class GitObjects {
    private final GitClient git;

    GitObjects(GitClient git) {
        this.git = git;
    }

    /** Immediate children of {@code path} in {@code rev}'s tree (root when blank), folders first. */
    List<TreeEntry> listTree(String rev, String path) {
        boolean atRoot = path == null || path.isBlank();
        String spec = atRoot ? rev : rev + ":" + path;
        String prefix = atRoot ? "" : (path.endsWith("/") ? path : path + "/");
        List<TreeEntry> out = new ArrayList<>();
        for (String rec : git.read(git.cmd("ls-tree", "-z", "-l", spec)).out().split("\0")) {
            if (rec.isBlank()) continue;
            int tab = rec.indexOf('\t');
            if (tab < 0) continue;
            String[] f = rec.substring(0, tab).trim().split("\\s+");
            if (f.length < 3) continue;
            long size = f.length > 3 && !f[3].equals("-") ? Long.parseLong(f[3]) : -1;
            String name = rec.substring(tab + 1);
            out.add(new TreeEntry(prefix + name, name, f[0], f[1], size));
        }
        out.sort(Comparator.comparing(TreeEntry::directory).reversed()
                .thenComparing(TreeEntry::name, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    /**
     * Blob of one file at a commit.
     *
     * @param maxBytes files larger than this are reported as truncated without being read
     */
    FileContent fileContent(String rev, String path, int maxBytes) {
        String spec = rev + ":" + path;
        long size = Long.parseLong(git.read(git.cmd("cat-file", "-s", spec)).out().strip());
        if (size > maxBytes) return new FileContent(size, false, true, new byte[0]);
        byte[] data = git.read(git.cmd("cat-file", "blob", spec)).stdout();
        return new FileContent(size, isBinary(data), false, data);
    }

    /** Raw bytes of a blob, whatever its size. */
    byte[] fileBytes(String rev, String path) {
        return git.read(git.cmd("cat-file", "blob", rev + ":" + path)).stdout();
    }

    /** Writes the file as it was at {@code rev} to {@code target}. */
    void saveFile(String rev, String path, Path target) {
        try {
            Files.write(target, fileBytes(rev, path));
        } catch (java.io.IOException e) {
            throw new GitException("cannot write " + target + ": " + e.getMessage(), e);
        }
    }

    /** A NUL byte means binary, except for UTF-16 text, which always contains NULs (as the resolver already knows). */
    private static boolean isBinary(byte[] data) {
        if (TextEncoding.hasUtf16Bom(data)) return false;
        int n = Math.min(data.length, 8000);
        for (int i = 0; i < n; i++) if (data[i] == 0) return true;
        return false;
    }
}
