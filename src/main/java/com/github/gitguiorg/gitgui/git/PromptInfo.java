package com.github.gitguiorg.gitgui.git;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parsed form of an askpass prompt: what git or ssh is asking for, and for which host.
 * Only used to render a friendlier dialog; unknown prompts fall back to {@link Kind#OTHER}.
 */
public record PromptInfo(Kind kind, String protocol, String host, String detail) {
    public enum Kind { USERNAME, PASSWORD, PASSPHRASE, HOST_KEY, OTHER }

    private static final Pattern URL = Pattern.compile("([a-z][a-z0-9+.-]*)://(?:[^@/\\s]*@)?([^/\\s']+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern HOST_KEY_HOST = Pattern.compile("authenticity of host '([^']+)'",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FINGERPRINT = Pattern.compile("\\b(SHA256:[A-Za-z0-9+/=]+)");
    private static final Pattern KEY_PATH = Pattern.compile("passphrase for key '([^']+)'",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SSH_PASSWORD = Pattern.compile("([\\w.@-]+@[\\w.-]+)'s password",
            Pattern.CASE_INSENSITIVE);

    public static PromptInfo parse(String prompt) {
        String p = prompt == null ? "" : prompt.strip();
        String lower = p.toLowerCase(Locale.ROOT);

        if (lower.contains("are you sure you want to continue connecting") || lower.contains("authenticity of host")) {
            String raw = HOST_KEY_HOST.matcher(p).results().findFirst().map(m -> m.group(1)).orElse(null);
            String host = raw == null ? null : raw.split(" \\(")[0].strip();
            String fingerprint = FINGERPRINT.matcher(p).results().findFirst().map(m -> m.group(1)).orElse(null);
            return new PromptInfo(Kind.HOST_KEY, "ssh", host, fingerprint);
        }

        Matcher u = URL.matcher(p);
        String protocol = null;
        String host = null;
        if (u.find()) {
            protocol = u.group(1).toLowerCase(Locale.ROOT);
            host = u.group(2);
        }
        if (lower.startsWith("username") || lower.contains("username for")) {
            return new PromptInfo(Kind.USERNAME, protocol, host, null);
        }
        if (lower.startsWith("password") || lower.contains("password for")) {
            return new PromptInfo(Kind.PASSWORD, protocol, host, null);
        }
        Matcher sshPassword = SSH_PASSWORD.matcher(p);
        if (sshPassword.find()) {
            String userHost = sshPassword.group(1);
            return new PromptInfo(Kind.PASSWORD, "ssh", userHost.substring(userHost.indexOf('@') + 1), userHost);
        }
        if (lower.contains("passphrase")) {
            String key = KEY_PATH.matcher(p).results().findFirst().map(m -> m.group(1)).orElse(null);
            return new PromptInfo(Kind.PASSPHRASE, protocol, host, key);
        }
        return new PromptInfo(Kind.OTHER, protocol, host, null);
    }

    /** True when ssh is asking whether to trust a host key for the first time. */
    public boolean hostKey() {
        return kind == Kind.HOST_KEY;
    }

    /** The key fingerprint of a host-key prompt; empty for any other prompt, whose detail is not a fingerprint. */
    public Optional<String> fingerprint() {
        return kind == Kind.HOST_KEY ? Optional.ofNullable(detail) : Optional.empty();
    }

    /** e.g. {@code github.com (HTTPS)} or {@code git needs your input} when the host is unknown. */
    public String title() {
        if (host == null || host.isBlank()) return "git needs your input";
        return protocol == null || protocol.isBlank() ? host : host + " (" + protocol.toUpperCase(Locale.ROOT) + ")";
    }
}
