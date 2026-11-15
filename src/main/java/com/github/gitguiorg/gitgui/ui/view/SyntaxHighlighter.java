package com.github.gitguiorg.gitgui.ui.view;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Small line-based lexer for the diff view. It knows keywords, strings, numbers and comments of the common
 * languages; multi-line constructs are only recognised within a single line (a diff shows lines out of context).
 */
final class SyntaxHighlighter {
    private SyntaxHighlighter() {}

    record Span(int start, int end, String style) {}

    private record Lang(Set<String> keywords, String[] lineComments, boolean blockComments, String quotes,
                        boolean types, boolean markup, boolean keys) {}

    private static final Set<String> C_LIKE = Set.of(
            "abstract", "as", "async", "await", "break", "case", "catch", "class", "const", "continue", "default", "defer",
            "do", "else", "enum", "export", "extends", "false", "final", "finally", "fn", "for", "func", "function", "go",
            "if", "implements", "import", "in", "instanceof", "interface", "let", "match", "mod", "namespace", "new",
            "null", "nil", "override", "package", "private", "protected", "public", "pub", "readonly", "record", "return",
            "sealed", "static", "struct", "super", "switch", "this", "throw", "throws", "trait", "true", "try", "type",
            "typeof", "undefined", "unsafe", "use", "using", "val", "var", "virtual", "void", "volatile", "when", "where",
            "while", "yield", "int", "long", "short", "byte", "char", "float", "double", "boolean", "bool", "string",
            "synchronized", "native", "transient", "is", "out", "ref", "internal", "operator", "inline", "typedef", "extern");
    private static final Set<String> PYTHON = Set.of(
            "and", "as", "assert", "async", "await", "break", "class", "continue", "def", "del", "elif", "else", "except",
            "finally", "for", "from", "global", "if", "import", "in", "is", "lambda", "None", "nonlocal", "not", "or",
            "pass", "raise", "return", "self", "True", "False", "try", "while", "with", "yield");
    private static final Set<String> SQL = Set.of(
            "select", "from", "where", "and", "or", "not", "null", "insert", "into", "values", "update", "set", "delete",
            "create", "table", "alter", "drop", "index", "join", "left", "right", "inner", "outer", "on", "group", "by",
            "order", "having", "limit", "as", "distinct", "union", "primary", "key", "foreign", "references", "default",
            "begin", "end", "case", "when", "then", "else", "in", "like", "is", "exists", "view", "procedure", "function");
    private static final Set<String> SHELL = Set.of(
            "if", "then", "else", "elif", "fi", "for", "do", "done", "while", "case", "esac", "function", "in", "return",
            "exit", "export", "local", "echo", "set", "unset", "param", "foreach", "switch", "try", "catch", "finally");
    private static final Set<String> CONSTANTS = Set.of("true", "false", "null");
    private static final Set<String> NONE = Set.of();

    private static final String[] SLASH = {"//"};
    private static final String[] HASH = {"#"};
    private static final String[] DASH = {"--"};
    private static final String[] SEMI = {";", "#"};

    private static final Lang CLIKE = new Lang(C_LIKE, SLASH, true, "\"'`", true, false, false);
    private static final Lang PY = new Lang(PYTHON, HASH, false, "\"'", false, false, false);
    private static final Lang SQL_LANG = new Lang(SQL, DASH, true, "\"'", false, false, false);
    private static final Lang SH = new Lang(SHELL, HASH, false, "\"'", false, false, false);
    private static final Lang JSON = new Lang(CONSTANTS, new String[0], false, "\"", false, false, true);
    private static final Lang YAML = new Lang(CONSTANTS, HASH, false, "\"'", false, false, true);
    private static final Lang INI = new Lang(NONE, SEMI, false, "\"'", false, false, true);
    private static final Lang XML = new Lang(NONE, new String[0], false, "\"'", false, true, false);
    private static final Lang CSS = new Lang(NONE, new String[0], true, "\"'", false, false, true);

    private static final Map<String, Lang> BY_EXT = Map.ofEntries(
            Map.entry("java", CLIKE), Map.entry("js", CLIKE), Map.entry("jsx", CLIKE), Map.entry("mjs", CLIKE),
            Map.entry("ts", CLIKE), Map.entry("tsx", CLIKE), Map.entry("c", CLIKE), Map.entry("h", CLIKE),
            Map.entry("cpp", CLIKE), Map.entry("cc", CLIKE), Map.entry("hpp", CLIKE), Map.entry("cs", CLIKE),
            Map.entry("go", CLIKE), Map.entry("rs", CLIKE), Map.entry("kt", CLIKE), Map.entry("kts", CLIKE),
            Map.entry("swift", CLIKE), Map.entry("scala", CLIKE), Map.entry("groovy", CLIKE), Map.entry("php", CLIKE),
            Map.entry("py", PY), Map.entry("rb", PY),
            Map.entry("sql", SQL_LANG),
            Map.entry("sh", SH), Map.entry("bash", SH), Map.entry("ps1", SH), Map.entry("psm1", SH),
            Map.entry("json", JSON), Map.entry("yml", YAML), Map.entry("yaml", YAML),
            Map.entry("properties", INI), Map.entry("ini", INI), Map.entry("toml", INI), Map.entry("conf", INI),
            Map.entry("xml", XML), Map.entry("html", XML), Map.entry("htm", XML), Map.entry("fxml", XML),
            Map.entry("xsd", XML), Map.entry("svg", XML), Map.entry("pom", XML), Map.entry("csproj", XML),
            Map.entry("css", CSS), Map.entry("scss", CSS), Map.entry("less", CSS),
            Map.entry("gradle", CLIKE));

    /** Files without an extension that still deserve highlighting. */
    private static final Map<String, Lang> BY_NAME = Map.of(
            "dockerfile", SH, "makefile", SH, "cmakelists.txt", SH, "gemfile", PY, ".gitignore", INI);

    /** True when we know how to highlight this file. */
    static boolean supports(String path) {
        return lang(path) != null;
    }

    private static Lang lang(String path) {
        if (path == null) return null;
        String name = path.substring(Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1).toLowerCase(Locale.ROOT);
        Lang byName = BY_NAME.get(name);
        if (byName != null) return byName;
        int dot = name.lastIndexOf('.');
        return dot < 0 ? null : BY_EXT.get(name.substring(dot + 1));
    }

    /** Token spans of one source line (start inclusive, end exclusive); the rest of the line has no token style. */
    static List<Span> highlight(String path, String line) {
        Lang l = lang(path);
        return l == null ? List.of() : lex(l, line);
    }

    private static List<Span> lex(Lang l, String s) {
        List<Span> out = new ArrayList<>();
        int n = s.length();
        int i = 0;
        String trimmed = s.stripLeading();
        if (l.blockComments() && (trimmed.startsWith("* ") || trimmed.equals("*") || trimmed.startsWith("*/"))) {
            out.add(new Span(0, n, "tok-com"));
            return out;
        }
        while (i < n) {
            char c = s.charAt(i);
            if (startsLineComment(l, s, i)) {
                out.add(new Span(i, n, "tok-com"));
                return out;
            }
            if (l.blockComments() && s.startsWith("/*", i)) {
                int end = s.indexOf("*/", i + 2);
                int stop = end < 0 ? n : end + 2;
                out.add(new Span(i, stop, "tok-com"));
                i = stop;
                continue;
            }
            if (l.markup() && s.startsWith("<!--", i)) {
                int end = s.indexOf("-->", i + 4);
                int stop = end < 0 ? n : end + 3;
                out.add(new Span(i, stop, "tok-com"));
                i = stop;
                continue;
            }
            if (l.markup() && c == '<') {
                int j = i + 1;
                if (j < n && (s.charAt(j) == '/' || s.charAt(j) == '?' || s.charAt(j) == '!')) j++;
                int k = j;
                while (k < n && (Character.isLetterOrDigit(s.charAt(k)) || ":-_.".indexOf(s.charAt(k)) >= 0)) k++;
                if (k > j) out.add(new Span(i, k, "tok-kw"));
                i = Math.max(k, i + 1);
                continue;
            }
            if (l.quotes().indexOf(c) >= 0) {
                int j = i + 1;
                while (j < n && s.charAt(j) != c) j += s.charAt(j) == '\\' ? 2 : 1;
                int stop = Math.min(n, j + 1);
                boolean key = l.keys() && stop < n && s.substring(stop).stripLeading().startsWith(":");
                out.add(new Span(i, stop, key ? "tok-key" : "tok-str"));
                i = stop;
                continue;
            }
            if (Character.isDigit(c) && (i == 0 || !Character.isLetterOrDigit(s.charAt(i - 1)) && s.charAt(i - 1) != '_')) {
                int j = i + 1;
                while (j < n && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '.' || s.charAt(j) == '_')) j++;
                out.add(new Span(i, j, "tok-num"));
                i = j;
                continue;
            }
            if (Character.isLetter(c) || c == '_' || c == '$' || c == '@') {
                int j = i + 1;
                while (j < n && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '_' || s.charAt(j) == '$')) j++;
                String word = s.substring(i, j);
                String key = l.keywords().contains(word) ? word : word.toLowerCase(Locale.ROOT);
                if (l.keywords().contains(word) || (l.keywords() == SQL && SQL.contains(key))) {
                    out.add(new Span(i, j, "tok-kw"));
                } else if (c == '@') {
                    out.add(new Span(i, j, "tok-type"));
                } else if (l.types() && Character.isUpperCase(c) && word.length() > 1) {
                    out.add(new Span(i, j, "tok-type"));
                } else if (l.keys() && !l.markup() && isKeyBeforeColon(s, j)) {
                    out.add(new Span(i, j, "tok-key"));
                }
                i = j;
                continue;
            }
            i++;
        }
        return out;
    }

    private static boolean startsLineComment(Lang l, String s, int i) {
        for (String lc : l.lineComments()) {
            if (!s.startsWith(lc, i)) continue;
            // "#" / "--" / ";" are only comments at a token boundary, not inside URLs or identifiers
            if (lc.length() == 1 && i > 0 && !Character.isWhitespace(s.charAt(i - 1))) continue;
            // "http://…" in a config value is a URL, not the start of a // comment
            if (lc.equals("//") && i > 0 && s.charAt(i - 1) == ':') continue;
            return true;
        }
        return false;
    }

    private static boolean isKeyBeforeColon(String s, int end) {
        String rest = s.substring(end).stripLeading();
        // "://" belongs to a URL (YAML/INI values such as "homepage: https://…"), not to a key
        return (rest.startsWith(":") && !rest.startsWith("://")) || rest.startsWith("=");
    }
}
