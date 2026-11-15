package com.github.gitguiorg.gitgui.git;

import com.github.gitguiorg.gitgui.model.ConflictVersions;

/** Conflict inspection and resolution of a {@link GitClient}. */
final class GitConflicts {
    private final GitClient git;

    GitConflicts(GitClient git) {
        this.git = git;
    }

    private byte[] stageBlob(int stage, String path) {
        GitResult r = git.readRaw(git.cmd("cat-file", "blob", ":" + stage + ":" + path));
        return r.exitCode() == 0 ? r.stdout() : null;
    }

    /** Base (stage 1), ours (stage 2) and theirs (stage 3) of a conflicted path; missing stages are null. */
    ConflictVersions versions(String path) {
        return new ConflictVersions(stageBlob(1, path), stageBlob(2, path), stageBlob(3, path));
    }

    /** Writes the resolved content to the working tree and stages it. */
    void resolveWithContent(String path, byte[] content) {
        git.replaceFileAndStage(path, content);
    }

    /** Resolves a conflicted path by taking one whole side, then stages it. */
    void takeSide(String path, boolean ours) {
        git.write(git.cmd("checkout", ours ? "--ours" : "--theirs", "--", path));
        git.write(git.cmd("add", "--", path));
    }

    /** Resolves a conflicted path by deleting it. */
    void delete(String path) {
        git.write(git.cmd("rm", "-f", "--", path));
    }
}
