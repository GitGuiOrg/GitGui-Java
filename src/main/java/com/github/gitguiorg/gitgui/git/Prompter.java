package com.github.gitguiorg.gitgui.git;

import java.util.Optional;

/** Answers prompts raised by git or ssh (user name, password, passphrase, host key confirmation). */
@FunctionalInterface
public interface Prompter {
    /**
     * Blocks until the user answered.
     *
     * @param prompt the text git printed, e.g. {@code Password for 'https://host': }
     * @param secret true when the answer must not be echoed
     * @return the answer, or empty when the user cancelled
     */
    Optional<String> ask(String prompt, boolean secret);

    /** Heuristic: prompts asking for a password, passphrase or PIN are secret. */
    static boolean isSecret(String prompt) {
        String p = prompt.toLowerCase(java.util.Locale.ROOT);
        return p.contains("password") || p.contains("passphrase") || p.contains("pin for") || p.contains("token");
    }
}
