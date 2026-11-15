package com.github.gitguiorg.gitgui.ui.view;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CommandPaletteTest {
    private static CommandPalette.Command cmd(String title, String category, String shortcut) {
        return new CommandPalette.Command(title, category, shortcut, () -> { });
    }

    private static List<String> titles(List<CommandPalette.Command> commands) {
        return commands.stream().map(CommandPalette.Command::title).toList();
    }

    private final List<CommandPalette.Command> all = List.of(
            cmd("Open repository…", "Repository", "Ctrl+O"),
            cmd("Clone repository…", "Repository", "Ctrl+Shift+O"),
            cmd("New branch…", "Branch", ""),
            cmd("Commit changes…", "Commit", ""),
            cmd("Push", "Network", ""));

    @Test
    void emptyQueryKeepsEveryCommandInDeclaredOrder() {
        assertThat(titles(CommandPalette.filter(all, "", Map.of()))).containsExactlyElementsOf(titles(all));
        assertThat(titles(CommandPalette.filter(all, "   ", Map.of()))).containsExactlyElementsOf(titles(all));
    }

    @Test
    void matchesEveryTokenAcrossTitleCategoryAndShortcut() {
        assertThat(titles(CommandPalette.filter(all, "open", Map.of()))).containsExactly("Open repository…");
        assertThat(titles(CommandPalette.filter(all, "new branch", Map.of()))).containsExactly("New branch…");
        assertThat(titles(CommandPalette.filter(all, "CTRL+O", Map.of()))).containsExactly("Open repository…");
        assertThat(titles(CommandPalette.filter(all, "commit changes", Map.of()))).containsExactly("Commit changes…");
        assertThat(titles(CommandPalette.filter(all, "nope", Map.of()))).isEmpty();
    }

    @Test
    void mostUsedComesFirstThenDeclaredOrderBreaksTies() {
        List<CommandPalette.Command> ordered =
                CommandPalette.filter(all, "repository", Map.of("Repository/Clone repository…", 3));
        assertThat(titles(ordered)).containsExactly("Clone repository…", "Open repository…");
    }
}