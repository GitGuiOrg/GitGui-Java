package com.github.gitguiorg.gitgui.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class DateFormatsTest {

    @Test
    void absoluteDatesUseTheChosenZone() {
        DateFormats.configure(DateFormats.ABSOLUTE, DateFormats.UTC);
        Instant t = Instant.parse("2024-05-01T10:20:30Z");
        assertThat(DateFormats.dateTime(t)).isEqualTo("2024-05-01 10:20");
        assertThat(DateFormats.dateTimeSeconds(t)).isEqualTo("2024-05-01 10:20:30");
        assertThat(DateFormats.date(t)).isEqualTo("2024-05-01");
        assertThat(DateFormats.zone()).isEqualTo(java.time.ZoneOffset.UTC);
        assertThat(DateFormats.isRelative()).isFalse();
        assertThat(DateFormats.dateTime(null)).isEmpty();
    }

    @Test
    void relativeDatesHaveSharedWording() {
        Instant now = Instant.parse("2024-05-01T12:00:00Z");
        assertThat(DateFormats.relative(now.minusSeconds(5), now)).isEqualTo("just now");
        assertThat(DateFormats.relative(now.minus(Duration.ofMinutes(5)), now)).isEqualTo("5 min ago");
        assertThat(DateFormats.relative(now.minus(Duration.ofHours(3)), now)).isEqualTo("3 h ago");
        assertThat(DateFormats.relative(now.minus(Duration.ofDays(1)), now)).isEqualTo("1 day ago");
        assertThat(DateFormats.relative(now.minus(Duration.ofDays(10)), now)).isEqualTo("10 days ago");
        assertThat(DateFormats.relative(now.minus(Duration.ofDays(61)), now)).isEqualTo("2 months ago");
        assertThat(DateFormats.relative(now.minus(Duration.ofDays(359)), now)).isEqualTo("11 months ago");
        // 360-364 days must not become "0 years ago"
        assertThat(DateFormats.relative(now.minus(Duration.ofDays(360)), now)).isEqualTo("1 year ago");
        assertThat(DateFormats.relative(now.minus(Duration.ofDays(364)), now)).isEqualTo("1 year ago");
        assertThat(DateFormats.relative(now.minus(Duration.ofDays(365)), now)).isEqualTo("1 year ago");
        assertThat(DateFormats.relative(now.minus(Duration.ofDays(730)), now)).isEqualTo("2 years ago");
        assertThat(DateFormats.relative(now.minus(Duration.ofDays(800)), now)).isEqualTo("2 years ago");
        // a clock skew must not produce "-1 min ago"
        assertThat(DateFormats.relative(now.plusSeconds(30), now)).isEqualTo("just now");
    }

    @Test
    void relativeModeIsUsedByThePublicFormatters() {
        DateFormats.configure(DateFormats.RELATIVE, DateFormats.LOCAL);
        assertThat(DateFormats.isRelative()).isTrue();
        assertThat(DateFormats.dateTime(Instant.now().minus(Duration.ofHours(2)))).isEqualTo("2 h ago");
        DateFormats.configure(DateFormats.ABSOLUTE, DateFormats.LOCAL);   // leave the default for other tests
    }
}
