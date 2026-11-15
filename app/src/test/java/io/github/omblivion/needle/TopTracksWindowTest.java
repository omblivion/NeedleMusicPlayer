package io.github.omblivion.needle;

import static org.junit.Assert.assertEquals;

import io.github.omblivion.needle.model.TopTracksWindow;

import org.junit.Test;

import java.time.Instant;
import java.time.ZoneId;

public class TopTracksWindowTest {
    @Test public void absentOrInvalidPreferenceDefaultsTo52Weeks() {
        assertEquals(TopTracksWindow.WEEKS_52, TopTracksWindow.fromPreference(null));
        assertEquals(TopTracksWindow.WEEKS_52, TopTracksWindow.fromPreference("invalid"));
        assertEquals(TopTracksWindow.ALL_TIME, TopTracksWindow.fromPreference("all_time"));
    }

    @Test public void presetTokensRoundTripInApprovedOrder() {
        String[] expected = {"all_time", "1y", "52w", "6m", "4m", "3m", "2m", "30d", "7d", "24h"};
        TopTracksWindow[] windows = TopTracksWindow.values();
        assertEquals(expected.length, windows.length);
        for (int i = 0; i < windows.length; i++) {
            assertEquals(expected[i], windows[i].preferenceValue);
            assertEquals(windows[i], TopTracksWindow.fromPreference(expected[i]));
        }
    }

    @Test public void shortWindowsAreElapsedNotCalendarAligned() {
        long now = Instant.parse("2026-11-15T15:23:10Z").toEpochMilli();
        ZoneId zone = ZoneId.of("Europe/Rome");
        assertEquals(Instant.parse("2026-11-14T15:23:10Z").toEpochMilli(),
                TopTracksWindow.HOURS_24.cutoffMillis(now, zone));
        assertEquals(Instant.parse("2026-11-08T15:23:10Z").toEpochMilli(),
                TopTracksWindow.DAYS_7.cutoffMillis(now, zone));
        assertEquals(Instant.parse("2026-10-16T15:23:10Z").toEpochMilli(),
                TopTracksWindow.DAYS_30.cutoffMillis(now, zone));
    }

    @Test public void yearAnd52WeeksAreDistinctAcrossLeapYear() {
        long now = Instant.parse("2024-03-01T12:00:00Z").toEpochMilli();
        ZoneId zone = ZoneId.of("UTC");
        assertEquals(Instant.parse("2023-03-01T12:00:00Z").toEpochMilli(),
                TopTracksWindow.YEAR.cutoffMillis(now, zone));
        assertEquals(Instant.parse("2023-03-03T12:00:00Z").toEpochMilli(),
                TopTracksWindow.WEEKS_52.cutoffMillis(now, zone));
    }

    @Test public void calendarMonthsHandleMonthEndAndDaylightSaving() {
        ZoneId zone = ZoneId.of("Europe/Rome");
        long now = Instant.parse("2026-04-30T10:00:00Z").toEpochMilli();
        assertEquals(Instant.parse("2026-02-28T11:00:00Z").toEpochMilli(),
                TopTracksWindow.MONTHS_2.cutoffMillis(now, zone));
        assertEquals(Instant.parse("2026-01-30T11:00:00Z").toEpochMilli(),
                TopTracksWindow.MONTHS_3.cutoffMillis(now, zone));
    }
}
