package com.github.gitguiorg.gitgui.service;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;

/**
 * Diff view options shared by every tab and written back to {@link Settings}, so they survive a restart.
 * The properties are the source of truth; changing one saves the settings file.
 */
public final class DiffPreferences {
    private final SettingsService service;
    private final BooleanProperty sideBySide = new SimpleBooleanProperty();
    private final BooleanProperty ignoreWhitespace = new SimpleBooleanProperty();
    private final BooleanProperty syntax = new SimpleBooleanProperty();
    private final BooleanProperty ignoreCrAtEol = new SimpleBooleanProperty();
    private final BooleanProperty ignoreBlankLines = new SimpleBooleanProperty();
    private final IntegerProperty contextLines = new SimpleIntegerProperty(3);
    private final IntegerProperty maxLines = new SimpleIntegerProperty(20_000);

    public DiffPreferences(SettingsService service) {
        this.service = service;
        Settings s = service.get();
        sideBySide.set(s.diffSideBySide);
        ignoreWhitespace.set(s.diffIgnoreWhitespace);
        syntax.set(s.diffSyntax);
        ignoreCrAtEol.set(s.diffIgnoreCrAtEol);
        ignoreBlankLines.set(s.diffIgnoreBlankLines);
        contextLines.set(Math.max(0, s.diffContextLines));
        maxLines.set(Math.max(100, s.diffMaxLines));

        sideBySide.addListener((o, a, b) -> { s.diffSideBySide = b; service.save(); });
        ignoreWhitespace.addListener((o, a, b) -> { s.diffIgnoreWhitespace = b; service.save(); });
        syntax.addListener((o, a, b) -> { s.diffSyntax = b; service.save(); });
        ignoreCrAtEol.addListener((o, a, b) -> { s.diffIgnoreCrAtEol = b; service.save(); });
        ignoreBlankLines.addListener((o, a, b) -> { s.diffIgnoreBlankLines = b; service.save(); });
        contextLines.addListener((o, a, b) -> {
            if (b.intValue() < 0) { contextLines.set(0); return; }
            s.diffContextLines = b.intValue();
            service.save();
        });
        maxLines.addListener((o, a, b) -> {
            if (b.intValue() < 100) { maxLines.set(100); return; }
            s.diffMaxLines = b.intValue();
            service.save();
        });
    }

    public BooleanProperty sideBySideProperty() { return sideBySide; }
    public BooleanProperty ignoreWhitespaceProperty() { return ignoreWhitespace; }
    public BooleanProperty syntaxProperty() { return syntax; }
    public BooleanProperty ignoreCrAtEolProperty() { return ignoreCrAtEol; }
    public BooleanProperty ignoreBlankLinesProperty() { return ignoreBlankLines; }
    public IntegerProperty contextLinesProperty() { return contextLines; }
    public IntegerProperty maxLinesProperty() { return maxLines; }

    /** True when an option hides part of the differences, so the shown diff cannot be applied as a patch. */
    public boolean ignoresDifferences() {
        return ignoreWhitespace.get() || ignoreCrAtEol.get() || ignoreBlankLines.get();
    }
}
