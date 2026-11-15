package com.github.gitguiorg.gitgui.model;

import java.util.Collection;

public record GitRef(Type type, String fullName, String shortName, String hash, String upstream, boolean head) {
    public enum Type { LOCAL_BRANCH, REMOTE_BRANCH, TAG }

    /**
     * The remote a remote-branch (or upstream) short name belongs to, choosing the longest configured name
     * that matches as a prefix so remotes such as {@code team/origin} are not cut at their first slash.
     */
    public static String remoteOf(String shortName, Collection<String> remotes) {
        String best = null;
        for (String r : remotes) {
            if (shortName.startsWith(r + "/") && (best == null || r.length() > best.length())) best = r;
        }
        if (best != null) return best;
        int slash = shortName.indexOf('/');
        return slash > 0 ? shortName.substring(0, slash) : shortName;
    }

    /** The branch part of a remote-branch (or upstream) short name, given the remote it belongs to. */
    public static String branchOf(String shortName, String remote) {
        return shortName.startsWith(remote + "/") ? shortName.substring(remote.length() + 1) : shortName;
    }

    /**
     * Refspec that pushes {@code localBranch} to the branch its upstream names, e.g. {@code fix:hotfix} for the
     * upstream {@code origin/hotfix}. A bare branch name would instead be refused by {@code push.default=simple}
     * or, when git is told to push anyway, create a second branch on the remote.
     */
    public static String pushRefspec(String localBranch, String upstream, Collection<String> remotes) {
        return localBranch + ":" + branchOf(upstream, remoteOf(upstream, remotes));
    }
}
