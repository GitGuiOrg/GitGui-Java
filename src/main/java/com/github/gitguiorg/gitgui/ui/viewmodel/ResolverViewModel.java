package com.github.gitguiorg.gitgui.ui.viewmodel;

import com.github.gitguiorg.gitgui.git.GitClient;
import com.github.gitguiorg.gitgui.model.ConflictVersions;
import com.github.gitguiorg.gitgui.model.Merge3;
import com.github.gitguiorg.gitgui.model.Operation;
import com.github.gitguiorg.gitgui.model.StatusEntry;
import com.github.gitguiorg.gitgui.ui.Fx;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;

/** State of the conflict resolver for one file. FX-thread only. */
public final class ResolverViewModel {
    /**
     * @param text true when the file can be merged line by line; otherwise only whole-side actions apply
     */
    public record Loaded(boolean text, boolean hasOurs, boolean hasTheirs, List<String> base, List<String> ours,
                         List<String> theirs, String eol, boolean trailingNewline, List<Merge3.Chunk> chunks,
                         com.github.gitguiorg.gitgui.util.TextEncoding.Decoded encoding) {}

    /**
     * @param oursPanel title of the ours pane
     * @param theirsPanel title of the theirs pane
     * @param oursAction label of the control that takes the ours side
     * @param theirsAction label of the control that takes the theirs side
     */
    public record Labels(String oursPanel, String theirsPanel, String oursAction, String theirsAction) {}

    private static final Labels NORMAL_LABELS =
            new Labels("Ours (current)", "Theirs (incoming)", "Ours", "Theirs");

    private record Snapshot(Loaded loaded, Labels labels) {}

    private final RepoViewModel repo;
    private final StatusEntry entry;
    private final ObjectProperty<Loaded> loaded = new SimpleObjectProperty<>();
    private Labels labels = NORMAL_LABELS;

    public ResolverViewModel(RepoViewModel repo, StatusEntry entry) {
        this.repo = repo;
        this.entry = entry;
    }

    public String path() { return entry.path(); }
    public ObjectProperty<Loaded> loadedProperty() { return loaded; }
    public Labels labels() { return labels; }

    /**
     * Side labels for the resolver. During a rebase git calls "ours" the branch being rebased (the target) and
     * "theirs" the commit that is being reapplied, so the plain labels would be misleading; for every other
     * operation (merge, cherry-pick, revert) the labels keep their usual meaning.
     */
    static Labels sideLabels(Operation op, String branch, String incoming) {
        if (op != Operation.REBASE) return NORMAL_LABELS;
        String b = branch == null || branch.isBlank() ? "current branch" : branch;
        String i = incoming == null || incoming.isBlank() ? "incoming commit" : incoming;
        return new Labels("Ours (rebased branch: " + b + ")", "Theirs (applied commit: " + i + ")",
                "Ours (rebased)", "Theirs (applied)");
    }

    public void load() {
        repo.session().async(() -> {
            GitClient git = repo.session().git();
            Operation op = git.operation();
            boolean rebase = op == Operation.REBASE;
            return new Snapshot(build(git.conflictVersions(entry.path())),
                    sideLabels(op, rebase ? git.rebaseBranch() : null, rebase ? git.rebaseIncoming() : null));
        }).whenComplete((s, err) -> Fx.run(() -> {
            if (err != null) {
                repo.reportError(err);
            } else {
                labels = s.labels();
                loaded.set(s.loaded());
            }
        }));
    }

    static Loaded build(ConflictVersions v) {
        boolean text = v.ours() != null && v.theirs() != null && !binary(v);
        if (!text) {
            return new Loaded(false, v.ours() != null, v.theirs() != null, List.of(), List.of(), List.of(), "\n", true,
                    List.of(), new com.github.gitguiorg.gitgui.util.TextEncoding.Decoded("", StandardCharsets.UTF_8, new byte[0]));
        }
        var oursDecoded = com.github.gitguiorg.gitgui.util.TextEncoding.decode(v.ours());
        var theirsDecoded = com.github.gitguiorg.gitgui.util.TextEncoding.decode(v.theirs());
        var baseDecoded = v.base() == null ? null : com.github.gitguiorg.gitgui.util.TextEncoding.decode(v.base());
        String ours = oursDecoded.text();
        String theirs = theirsDecoded.text();
        String base = baseDecoded == null ? "" : baseDecoded.text();
        String probe = ours.isEmpty() ? theirs : ours;
        List<String> b = Merge3.lines(base);
        List<String> o = Merge3.lines(ours);
        List<String> t = Merge3.lines(theirs);
        var encoding = new com.github.gitguiorg.gitgui.util.TextEncoding.Decoded("", oursDecoded.charset(), oursDecoded.bom());
        return new Loaded(true, true, true, b, o, t, Merge3.eolOf(probe), probe.endsWith("\n") || probe.isEmpty(),
                Merge3.merge(b, o, t), encoding);
    }

    /** Binary means a NUL byte, unless the file is UTF-16 (which always contains NULs). */
    private static boolean binary(ConflictVersions v) {
        for (byte[] side : new byte[][]{v.base(), v.ours(), v.theirs()}) {
            if (side == null || com.github.gitguiorg.gitgui.util.TextEncoding.hasUtf16Bom(side)) continue;
            for (byte b : side) if (b == 0) return true;
        }
        return false;
    }

    /** Writes the resolved text, stages the file, then runs {@code onDone} on the FX thread. */
    public void markResolved(String content, Runnable onDone) {
        Loaded l = loaded.get();
        byte[] bytes = l == null ? content.getBytes(StandardCharsets.UTF_8) : l.encoding().encode(content);
        exec(() -> repo.session().git().resolveWithContent(entry.path(), bytes), onDone);
    }

    public void takeSide(boolean ours, Runnable onDone) {
        exec(() -> repo.session().git().resolveTakeSide(entry.path(), ours), onDone);
    }

    public void delete(Runnable onDone) {
        exec(() -> repo.session().git().resolveDelete(entry.path()), onDone);
    }

    private void exec(Runnable op, Runnable onDone) {
        repo.session().mutate(op).whenComplete((v, err) -> {
            if (err != null) repo.reportError(err); else Fx.run(onDone);
        });
    }
}
