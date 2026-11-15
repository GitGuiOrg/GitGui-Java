package com.github.gitguiorg.gitgui.git;

import java.util.List;

public class GitTimeoutException extends GitException {
    public GitTimeoutException(List<String> command, String stderr) {
        super("git command timed out: " + String.join(" ", command), command, -1, stderr);
    }
}
