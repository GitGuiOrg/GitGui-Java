package com.github.gitguiorg.gitgui.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HashesTest {
    @Test
    void shortensFullHashesAndLeavesShortOnesAlone() {
        assertThat(Hashes.shortHash("0123456789abcdef0123456789abcdef01234567")).isEqualTo("0123456");
        assertThat(Hashes.shortHash("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"))
                .isEqualTo("0123456");
        assertThat(Hashes.shortHash("abc1234")).isEqualTo("abc1234");
        assertThat(Hashes.shortHash("abc")).isEqualTo("abc");
    }

    @Test
    void nullBecomesEmpty() {
        assertThat(Hashes.shortHash(null)).isEmpty();
        assertThat(Hashes.shortHash("")).isEmpty();
    }

    @Test
    void objectIdsMayBeSha1OrSha256() {
        String sha1 = "0123456789abcdef0123456789abcdef01234567";
        String sha256 = sha1 + "0123456789abcdef01234567";   // 64 characters
        assertThat(sha256).hasSize(64);
        assertThat(Hashes.isObjectId(sha1)).isTrue();
        assertThat(Hashes.isObjectId(sha256)).isTrue();
        assertThat(Hashes.isObjectId("abc1234")).isTrue();
        assertThat(Hashes.isObjectId("ABCDEF0")).isTrue();
    }

    @Test
    void everythingElseIsNotAnObjectId() {
        assertThat(Hashes.isObjectId(null)).isFalse();
        assertThat(Hashes.isObjectId("")).isFalse();
        assertThat(Hashes.isObjectId("abc123")).isFalse();          // too short
        assertThat(Hashes.isObjectId("(initial)")).isFalse();
        assertThat(Hashes.isObjectId("main")).isFalse();
        assertThat(Hashes.isObjectId("0123456789abcdef0123456789abcdef01234567x")).isFalse();
        assertThat(Hashes.isObjectId("0".repeat(65))).isFalse();
    }
}
