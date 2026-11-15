package com.github.gitguiorg.gitgui;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.gitguiorg.gitgui.git.*;
import com.github.gitguiorg.gitgui.model.*;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ParsersTest {

    @Test
    void logParser() {
        String out = "\u001eaaa\0bbb ccc\0Ann\0a@x.it\u0000" + "100\0HEAD -> main, tag: v1\0Subject\0Body line\n\n\0N"
                + "\u001ebbb\0\0Bob\0b@x.it\u0000" + "50\0\0Root\0\n\0G";
        List<Commit> c = LogParser.parse(out);
        assertThat(c).hasSize(2);
        assertThat(c.get(0).parents()).containsExactly("bbb", "ccc");
        assertThat(c.get(0).refs()).containsExactly("HEAD -> main", "tag: v1");
        assertThat(c.get(0).body()).isEqualTo("Body line");
        assertThat(c.get(0).isMerge()).isTrue();
        assertThat(c.get(1).parents()).isEmpty();
        assertThat(c.get(1).refs()).isEmpty();
        assertThat(c.get(0).signed()).isFalse();
        assertThat(c.get(1).signature()).isEqualTo("G");
        assertThat(c.get(1).signed()).isTrue();
    }

    @Test
    void statusParser() {
        String out = "# branch.oid abc\0# branch.head main\0# branch.upstream origin/main\0# branch.ab +2 -1\0"
                + "1 M. N... 100644 100644 100644 h1 h2 src/A.java\0"
                + "2 R. N... 100644 100644 100644 h1 h2 R100 new name.txt\0old name.txt\0"
                + "u UU N... 1 2 3 4 h1 h2 h3 conflict.txt\0"
                + "? new.txt\0";
        RepoStatus s = StatusParser.parse(out);
        assertThat(s.branch()).isEqualTo("main");
        assertThat(s.upstream()).isEqualTo("origin/main");
        assertThat(s.ahead()).isEqualTo(2);
        assertThat(s.behind()).isEqualTo(1);
        assertThat(s.entries()).hasSize(4);
        assertThat(s.entries().get(0).isStaged()).isTrue();
        assertThat(s.entries().get(0).isUnstaged()).isFalse();
        assertThat(s.entries().get(1).path()).isEqualTo("new name.txt");
        assertThat(s.entries().get(1).origPath()).isEqualTo("old name.txt");
        assertThat(s.hasConflicts()).isTrue();
        assertThat(s.entries().get(3).kind()).isEqualTo(StatusEntry.Kind.UNTRACKED);
    }

    static final String DIFF = """
            diff --git a/f.txt b/f.txt
            index 111..222 100644
            --- a/f.txt
            +++ b/f.txt
            @@ -1,3 +1,3 @@ sec
             a
            -b
            +B
             c
            @@ -10,2 +10,3 @@
             x
            +y
            +z
            \\ No newline at end of file
            diff --git a/img.png b/img.png
            new file mode 100644
            index 000..333
            Binary files /dev/null and b/img.png differ
            """;

    @Test
    void diffParser() {
        List<FileDiff> d = DiffParser.parse(DIFF);
        assertThat(d).hasSize(2);
        assertThat(d.get(0).path()).isEqualTo("f.txt");
        assertThat(d.get(0).hunks()).hasSize(2);
        assertThat(d.get(0).hunks().get(0).section()).isEqualTo(" sec");
        assertThat(d.get(0).hunks().get(1).lines()).hasSize(4);
        assertThat(d.get(1).binary()).isTrue();
        assertThat(d.get(1).newFile()).isTrue();
    }

    @Test
    void diffParserDecodesQuotedPaths() {
        String d = """
                diff --git "a/citt\\303\\240.txt" "b/citt\\303\\240.txt"
                index 111..222 100644
                --- "a/citt\\303\\240.txt"
                +++ "b/citt\\303\\240.txt"
                @@ -1 +1 @@
                -x
                +y
                """;
        FileDiff f = DiffParser.parse(d).get(0);
        assertThat(f.oldPath()).isEqualTo("città.txt");
        assertThat(f.newPath()).isEqualTo("città.txt");
        assertThat(f.path()).isEqualTo("città.txt");
    }

    @Test
    void diffParserKeepsUtf8WhenTheSampleCutSplitsACharacter() {
        // 6000 euro signs are 18000 bytes: the 16 KiB sample ends after one byte of a three-byte character
        String body = "€".repeat(6000);
        String d = "diff --git a/f.txt b/f.txt\n--- a/f.txt\n+++ b/f.txt\n@@ -0,0 +1 @@\n+" + body + "\n";
        FileDiff f = DiffParser.parse(d).get(0);
        assertThat(f.hunks().get(0).lines()).singleElement().satisfies(l -> assertThat(l.text()).isEqualTo(body));
    }

    @Test
    void binaryDiffWithoutRenderablePathKeepsSpaces() {
        String d = """
                diff --git a/img b/pic.bin b/img b/pic.bin
                index 111..222 100644
                GIT binary patch
                literal 3
                KcmZQzWCj2L2ml2D
                """;
        FileDiff f = DiffParser.parse(d).get(0);
        assertThat(f.binary()).isTrue();
        assertThat(f.oldPath()).isEqualTo("img b/pic.bin");
        assertThat(f.newPath()).isEqualTo("img b/pic.bin");
    }

    @Test
    void binaryDiffWithQuotedPathIsDecoded() {
        String d = """
                diff --git "a/bin\\303\\240ry" "b/bin\\303\\240ry"
                index 111..222 100644
                GIT binary patch
                literal 3
                KcmZQzWCj2L2ml2D
                """;
        FileDiff f = DiffParser.parse(d).get(0);
        assertThat(f.path()).isEqualTo("binàry");
    }

    @Test
    void patchBuilderLineSelection() {
        FileDiff f = DiffParser.parse(DIFF).get(0);
        // hunk 1: select only the added "y" (index 1); "z" is dropped, the no-newline marker with it
        String p = PatchBuilder.build(f, 1, Set.of(1), false).orElseThrow();
        assertThat(p).contains("@@ -10,1 +10,2 @@").doesNotContain("+z").doesNotContain("No newline");
        // nothing selected -> no patch
        assertThat(PatchBuilder.build(f, 0, Set.of(), false)).isEmpty();
        // forward mode: unselected removal becomes context
        String q = PatchBuilder.build(f, 0, Set.of(2), false).orElseThrow();
        assertThat(q).contains("\n b\n").contains("+B");
    }

    @Test
    void progressParser() {
        assertThat(ProgressParser.parse("Receiving objects:  45% (45/100), 1 MiB").orElseThrow())
                .isEqualTo(new GitProgress("Receiving objects", 45));
        assertThat(ProgressParser.parse("remote: Compressing objects: 100% (3/3)").orElseThrow().percent())
                .isEqualTo(100);
        assertThat(ProgressParser.parse("From github.com:x/y")).isEmpty();
        assertThat(ProgressParser.parse("Uploading LFS objects:  45% (5/11), 1.2 MB | 0 B/s").orElseThrow())
                .isEqualTo(new GitProgress("Uploading LFS objects", 45));
    }

    @Test
    void promptInfo() {
        PromptInfo user = PromptInfo.parse("Username for 'https://github.com': ");
        assertThat(user.kind()).isEqualTo(PromptInfo.Kind.USERNAME);
        assertThat(user.protocol()).isEqualTo("https");
        assertThat(user.host()).isEqualTo("github.com");
        assertThat(user.title()).isEqualTo("github.com (HTTPS)");

        PromptInfo password = PromptInfo.parse("Password for 'https://bob@example.org': ");
        assertThat(password.kind()).isEqualTo(PromptInfo.Kind.PASSWORD);
        assertThat(password.host()).isEqualTo("example.org");

        PromptInfo sshPassword = PromptInfo.parse("bob@git.example.org's password: ");
        assertThat(sshPassword.kind()).isEqualTo(PromptInfo.Kind.PASSWORD);
        assertThat(sshPassword.protocol()).isEqualTo("ssh");
        assertThat(sshPassword.host()).isEqualTo("git.example.org");

        PromptInfo passphrase = PromptInfo.parse("Enter passphrase for key '/home/bob/.ssh/id_ed25519': ");
        assertThat(passphrase.kind()).isEqualTo(PromptInfo.Kind.PASSPHRASE);
        assertThat(passphrase.detail()).isEqualTo("/home/bob/.ssh/id_ed25519");
        // The key path is a detail, not a host key fingerprint.
        assertThat(passphrase.fingerprint()).isEmpty();

        PromptInfo hostKey = PromptInfo.parse("""
                The authenticity of host 'github.com (140.82.121.4)' can't be established.
                ED25519 key fingerprint is SHA256:abcdef123456.
                Are you sure you want to continue connecting (yes/no/[fingerprint])? """);
        assertThat(hostKey.hostKey()).isTrue();
        assertThat(hostKey.host()).isEqualTo("github.com");
        assertThat(hostKey.fingerprint()).contains("SHA256:abcdef123456");

        PromptInfo other = PromptInfo.parse("Something else entirely?");
        assertThat(other.kind()).isEqualTo(PromptInfo.Kind.OTHER);
        assertThat(other.host()).isNull();
    }
}
