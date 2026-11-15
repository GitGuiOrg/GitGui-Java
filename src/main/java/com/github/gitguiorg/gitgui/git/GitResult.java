package com.github.gitguiorg.gitgui.git;

import java.nio.charset.StandardCharsets;
import java.util.List;

public record GitResult(List<String> command, int exitCode, byte[] stdout, String stderr) {
    public String out() {
        return new String(stdout, StandardCharsets.UTF_8);
    }

    public GitException toException() {
        String msg = stderr.isBlank() ? "git exited with code " + exitCode : stderr.strip();
        return new GitException(msg, command, exitCode, stderr);
    }
}
