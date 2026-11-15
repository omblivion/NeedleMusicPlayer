package io.github.omblivion.needle.model;

import androidx.annotation.StringRes;

import io.github.omblivion.needle.R;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/** Closed set of rolling windows; preference values are stable backup identifiers. */
public enum TopTracksWindow {
    ALL_TIME("all_time", R.string.top_window_all_time),
    YEAR("1y", R.string.top_window_year),
    WEEKS_52("52w", R.string.top_window_52_weeks),
    MONTHS_6("6m", R.string.top_window_6_months),
    MONTHS_4("4m", R.string.top_window_4_months),
    MONTHS_3("3m", R.string.top_window_3_months),
    MONTHS_2("2m", R.string.top_window_2_months),
    DAYS_30("30d", R.string.top_window_30_days),
    DAYS_7("7d", R.string.top_window_7_days),
    HOURS_24("24h", R.string.top_window_24_hours);

    public final String preferenceValue;
    @StringRes public final int labelRes;

    TopTracksWindow(String preferenceValue, @StringRes int labelRes) {
        this.preferenceValue = preferenceValue;
        this.labelRes = labelRes;
    }

    public static TopTracksWindow fromPreference(String value) {
        for (TopTracksWindow window : values()) {
            if (window.preferenceValue.equals(value)) return window;
        }
        return WEEKS_52;
    }

    public static boolean isValidPreference(String value) {
        for (TopTracksWindow window : values()) {
            if (window.preferenceValue.equals(value)) return true;
        }
        return false;
    }

    public long cutoffMillis(long now, ZoneId zone) {
        ZonedDateTime date = Instant.ofEpochMilli(now).atZone(zone);
        switch (this) {
            case ALL_TIME: return Long.MIN_VALUE;
            case YEAR: return date.minusYears(1).toInstant().toEpochMilli();
            case MONTHS_6: return date.minusMonths(6).toInstant().toEpochMilli();
            case MONTHS_4: return date.minusMonths(4).toInstant().toEpochMilli();
            case MONTHS_3: return date.minusMonths(3).toInstant().toEpochMilli();
            case MONTHS_2: return date.minusMonths(2).toInstant().toEpochMilli();
            case WEEKS_52: return now - Duration.ofDays(364).toMillis();
            case DAYS_30: return now - Duration.ofDays(30).toMillis();
            case DAYS_7: return now - Duration.ofDays(7).toMillis();
            case HOURS_24: return now - Duration.ofHours(24).toMillis();
            default: throw new IllegalStateException("Unknown top-track window");
        }
    }
}
