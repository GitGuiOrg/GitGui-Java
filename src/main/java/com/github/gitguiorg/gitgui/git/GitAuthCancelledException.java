package com.github.gitguiorg.gitgui.git;

import java.util.List;

/** The user dismissed an authentication prompt, so git could not finish the operation. */
public class GitAuthCancelledException extends GitException {
    public GitAuthCancelledException(List<String> command, String stderr) {
        super("Authentication cancelled: git could not finish without the requested credentials",
                command, -1, stderr);
    }
}
