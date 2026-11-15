package com.github.gitguiorg.gitgui.git;

/** Handle to cancel a running CLI command (kills the process). */
public final class CancelToken {
    private volatile Process process;
    private volatile boolean cancelled;

    void attach(Process p) {
        this.process = p;
        if (cancelled) p.destroyForcibly();
    }

    public void cancel() {
        cancelled = true;
        Process p = process;
        if (p != null) p.destroyForcibly();
    }

    public boolean isCancelled() {
        return cancelled;
    }
}
