package com.github.gitguiorg.gitgui;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.gitguiorg.gitgui.git.GitClient;
import com.github.gitguiorg.gitgui.git.GitCommand;
import com.github.gitguiorg.gitgui.git.GitRunner;
import com.github.gitguiorg.gitgui.model.FileContent;
import com.github.gitguiorg.gitgui.model.TreeEntry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommitFilesTest {
    @TempDir Path tmp;
    GitRunner runner = new GitRunner(null);
    Path dir;
    GitClient git;
    String first;

    @BeforeEach
    void setUp() throws Exception {
        dir = tmp.resolve("tree repo");
        git = GitClient.init(dir, runner);
        for (String[] kv : new String[][]{{"user.name", "T"}, {"user.email", "t@x.it"}, {"commit.gpgsign", "false"},
                {"core.autocrlf", "false"}}) {
            runner.run(GitCommand.in(dir).args("config", kv[0], kv[1]));
        }
        runner.run(GitCommand.in(dir).args("checkout", "-q", "-b", "main"));

        Files.createDirectories(dir.resolve("dir/sub"));
        Files.writeString(dir.resolve("a.txt"), "v1\n");
        Files.writeString(dir.resolve("dir/b.txt"), "b\n");
        Files.writeString(dir.resolve("dir/sub/c.txt"), "c\n");
        Files.write(dir.resolve("bin.dat"), new byte[]{'a', 0, 'b'});
        git.stage(List.of("a.txt", "dir/b.txt", "dir/sub/c.txt", "bin.dat"));
        git.commit("first", false);
        first = git.status().headOid();

        Files.writeString(dir.resolve("a.txt"), "v2\nlonger\n");
        Files.writeString(dir.resolve("later.txt"), "later\n");
        git.stage(List.of("a.txt", "later.txt"));
        git.commit("second", false);
    }

    @Test
    void listTreePutsFoldersFirst() {
        List<TreeEntry> root = git.listTree("HEAD", "");
        assertThat(root).extracting(TreeEntry::name)
                .containsExactly("dir", "a.txt", "bin.dat", "later.txt");
        assertThat(root.get(0).directory()).isTrue();
        assertThat(root.get(0).size()).isNegative();
        assertThat(root.get(1).directory()).isFalse();
        assertThat(root.get(1).size()).isEqualTo(10);
    }

    @Test
    void listTreeOfASubfolderUsesFullPaths() {
        List<TreeEntry> sub = git.listTree("HEAD", "dir");
        assertThat(sub).extracting(TreeEntry::path).containsExactly("dir/sub", "dir/b.txt");
        assertThat(sub.get(1).name()).isEqualTo("b.txt");
    }

    @Test
    void listTreeAtAnOlderCommit() {
        assertThat(git.listTree(first, "")).extracting(TreeEntry::name)
                .containsExactly("dir", "a.txt", "bin.dat");
    }

    @Test
    void fileContentIsReadAtTheGivenCommit() {
        FileContent now = git.fileContent("HEAD", "a.txt", 1_000);
        assertThat(now.text()).isEqualTo("v2\nlonger\n");
        assertThat(now.size()).isEqualTo(10);
        assertThat(now.binary()).isFalse();
        assertThat(now.truncated()).isFalse();

        assertThat(git.fileContent(first, "a.txt", 1_000).text()).isEqualTo("v1\n");
    }

    @Test
    void binaryAndLargeFilesAreNotShownAsText() {
        FileContent bin = git.fileContent("HEAD", "bin.dat", 1_000);
        assertThat(bin.binary()).isTrue();
        assertThat(bin.data()).isNotEmpty();

        FileContent big = git.fileContent("HEAD", "bin.dat", 2);
        assertThat(big.truncated()).isTrue();
        assertThat(big.size()).isEqualTo(3);
        assertThat(big.data()).isEmpty();
    }

    @Test
    void utf16FilesAreTextEvenThoughTheyContainNulBytes() throws Exception {
        byte[] text = "hello\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_16LE);
        byte[] withBom = new byte[text.length + 2];
        withBom[0] = (byte) 0xFF;
        withBom[1] = (byte) 0xFE;
        System.arraycopy(text, 0, withBom, 2, text.length);
        Files.write(dir.resolve("utf16.txt"), withBom);
        git.stage(List.of("utf16.txt"));
        git.commit("utf16", false);

        FileContent c = git.fileContent("HEAD", "utf16.txt", 10_000);

        assertThat(c.binary()).isFalse();
        assertThat(c.truncated()).isFalse();
        assertThat(c.text()).isEqualTo("hello\r\n");
    }

    @Test
    void saveFileWritesTheBlob() throws Exception {
        Path target = tmp.resolve("saved.dat");
        git.saveFile("HEAD", "bin.dat", target);
        assertThat(Files.readAllBytes(target)).containsExactly('a', (byte) 0, 'b');
    }
}
