package com.github.gitguiorg.gitgui.git;

import java.util.List;

public class GitCancelledException extends GitException {
    public GitCancelledException(List<String> command, String stderr) {
        super("git command cancelled: " + String.join(" ", command), command, -1, stderr);
    }
}
