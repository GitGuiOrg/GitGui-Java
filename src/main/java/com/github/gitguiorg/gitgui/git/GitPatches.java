package com.github.gitguiorg.gitgui.git;

import com.github.gitguiorg.gitgui.model.MergeOutcome;
import com.github.gitguiorg.gitgui.model.Operation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Patch creation and application of a {@link GitClient}. */
final class GitPatches {
    private final GitClient git;

    GitPatches(GitClient git) {
        this.git = git;
    }

    /** Writes the mbox patch of one commit ({@code git format-patch -1 <rev> --stdout}) to {@code file}. */
    void formatPatch(String rev, Path file) {
        writeBytes(file, git.read(git.cmd("format-patch", "-1", rev, "--stdout")).stdout());
    }

    /** Writes one patch per commit of the range (from the merge base with {@code mergeBase}) into {@code dir}. */
    void formatPatchRange(String a, String b, boolean mergeBase, Path dir) {
        git.write(git.cmd("format-patch", a + (mergeBase ? "..." : "..") + b, "-o", dir.toString(), "--quiet"));
    }

    private static void writeBytes(Path file, byte[] data) {
        try {
            Files.write(file, data);
        } catch (java.io.IOException e) {
            throw new GitException("cannot write " + file + ": " + e.getMessage(), e);
        }
    }

    /** Checks a patch against the working tree and tells whether it is a mailbox to apply with {@code git am}. */
    GitClient.PatchInfo patchInfo(Path file) {
        GitResult check = git.read(git.cmd("apply", "--check", "--stat", "--", file.toString()).allowExit(1));
        String stat = check.out().strip();
        String error = check.exitCode() == 0 ? "" : (check.stderr().isBlank() ? stat : check.stderr().strip());
        return new GitClient.PatchInfo(looksLikeMailbox(file), check.exitCode() == 0, stat, error);
    }

    private static boolean looksLikeMailbox(Path file) {
        try {
            byte[] head = new byte[5];
            try (var in = Files.newInputStream(file)) {
                int n = in.readNBytes(head, 0, head.length);
                return n == 5 && new String(head, StandardCharsets.US_ASCII).equals("From ");
            }
        } catch (java.io.IOException e) {
            throw new GitException("cannot read " + file + ": " + e.getMessage(), e);
        }
    }

    /** Applies a plain diff with {@code git apply}; git refuses conflicting hunks instead of leaving markers. */
    MergeOutcome applyPatchFile(Path file) {
        git.write(git.cmd("apply", "--whitespace=nowarn", "--", file.toString()));
        return MergeOutcome.DONE;
    }

    /** Applies an mbox patch with {@code git am}; a failure leaves an AM operation for the banner. */
    MergeOutcome applyMailPatch(Path file) {
        GitResult r = git.writeRaw(git.cmd("am", "--", file.toString()));
        if (r.exitCode() == 0) return MergeOutcome.DONE;
        if (git.operation() == Operation.AM) return MergeOutcome.CONFLICTS;
        throw r.toException();
    }
}
