package com.github.gitguiorg.gitgui;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.gitguiorg.gitgui.git.BlameParser;
import com.github.gitguiorg.gitgui.git.GitClient;
import com.github.gitguiorg.gitgui.git.GitCommand;
import com.github.gitguiorg.gitgui.git.GitRunner;
import com.github.gitguiorg.gitgui.model.BlameLine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BlameTest {
    @TempDir Path tmp;

    @Test
    void parsesPorcelain() {
        String h1 = "a".repeat(40);
        String h2 = "b".repeat(40);
        String out = h1 + " 1 1 2\nauthor Alice\nauthor-mail <a@x>\nauthor-time 100\nauthor-tz +0000\nsummary first\nfilename f\n\tline one\n"
                + h1 + " 2 2\n\tline two\n"
                + h2 + " 3 3 1\nauthor Bob\nauthor-time 200\nsummary second\nfilename f\n\tline three\n";
        List<BlameLine> l = BlameParser.parse(out);
        assertThat(l).hasSize(3);
        assertThat(l.get(1).author()).isEqualTo("Alice");
        assertThat(l.get(1).line()).isEqualTo(2);
        assertThat(l.get(2).author()).isEqualTo("Bob");
        assertThat(l.get(2).summary()).isEqualTo("second");
        assertThat(l.get(2).text()).isEqualTo("line three");
        assertThat(l.get(0).uncommitted()).isFalse();
        assertThat(new BlameLine(1, "0".repeat(40), "x", java.time.Instant.EPOCH, "", "").uncommitted()).isTrue();
    }

    @Test
    void realBlameAndFileHistory() throws Exception {
        GitRunner r = new GitRunner(null);
        Path dir = tmp.resolve("repo");
        GitClient git = GitClient.init(dir, r);
        for (String[] kv : new String[][]{{"user.email", "t@x.it"}, {"commit.gpgsign", "false"}, {"core.autocrlf", "false"}}) {
            r.run(GitCommand.in(dir).args("config", kv[0], kv[1]));
        }
        r.run(GitCommand.in(dir).args("config", "user.name", "Alice"));
        Files.writeString(dir.resolve("f.txt"), "one\ntwo\n");
        git.stage(List.of("f.txt"));
        git.commit("first", false);
        r.run(GitCommand.in(dir).args("config", "user.name", "Bob"));
        Files.writeString(dir.resolve("f.txt"), "one\ntwo\nthree\n");
        git.stage(List.of("f.txt"));
        git.commit("second", false);
        Files.writeString(dir.resolve("f.txt"), "one\ntwo\nthree\nfour\n");

        List<BlameLine> blame = git.blame("f.txt", null);
        assertThat(blame).extracting(BlameLine::text).containsExactly("one", "two", "three", "four");
        assertThat(blame).extracting(BlameLine::author).containsExactly("Alice", "Alice", "Bob", "Not Committed Yet");
        assertThat(blame.get(3).uncommitted()).isTrue();
        assertThat(blame.get(2).summary()).isEqualTo("second");

        String first = git.log(0, 10, false).get(1).hash();
        assertThat(git.blame("f.txt", first)).hasSize(2);

        assertThat(git.fileHistory("f.txt", 0, 10)).extracting(c -> c.subject()).containsExactly("second", "first");
        String second = git.log(0, 1, false).get(0).hash();
        assertThat(git.diffCommitFile(second, "f.txt", 3)).hasSize(1);
        assertThat(git.diffCommitFile(second, "nope.txt", 3)).isEmpty();
    }
}
