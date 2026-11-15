package com.github.gitguiorg.gitgui.git;

import java.util.List;

public class GitException extends RuntimeException {
    private final List<String> command;
    private final int exitCode;
    private final String stderr;

    public GitException(String message, List<String> command, int exitCode, String stderr) {
        super(message);
        this.command = List.copyOf(command);
        this.exitCode = exitCode;
        this.stderr = stderr;
    }

    public GitException(String message, Throwable cause) {
        super(message, cause);
        this.command = List.of();
        this.exitCode = -1;
        this.stderr = "";
    }

    public List<String> command() { return command; }
    public int exitCode() { return exitCode; }
    public String stderr() { return stderr; }
}
