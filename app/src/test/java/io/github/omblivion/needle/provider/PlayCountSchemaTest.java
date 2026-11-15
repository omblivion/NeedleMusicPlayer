package io.github.omblivion.needle.provider;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.sqlite.SQLiteDatabase;

import io.github.omblivion.needle.App;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = App.class)
public class PlayCountSchemaTest {
    @Test
    public void missingSongIdReportsRequiredColumnInsteadOfReadingInvalidIndex() {
        assertMissingColumn("song_id", new String[] {"week_index", "play_count_score"},
                new Object[] {0, 0});
    }

    @Test
    public void missingWeekIndexReportsRequiredColumnInsteadOfReadingInvalidIndex() {
        assertMissingColumn("week_index", new String[] {"song_id", "play_count_score"},
                new Object[] {42L, 0});
    }

    @Test
    public void repeatedPlaysPreserveCountAndScore() {
        try (SongPlayCountStore store = new SongPlayCountStore(RuntimeEnvironment.getApplication())) {
            store.bumpPlayCount(42);
            store.bumpPlayCount(42);
            try (Cursor cursor = store.getReadableDatabase().rawQuery(
                    "SELECT week0, play_count_score FROM song_play_count WHERE song_id=42", null)) {
                assertTrue(cursor.moveToFirst());
                assertEquals(2, cursor.getInt(0));
                assertEquals(150f, cursor.getFloat(1), .001f);
            }
        }
    }

    @Test
    public void lastWeeksPlaysShiftWithoutBeingLostWhenTrackPlaysAgain() {
        try (SongPlayCountStore store = new SongPlayCountStore(RuntimeEnvironment.getApplication())) {
            int week = ReflectionHelpers.getField(store, "mNumberOfWeeksSinceEpoch");
            store.getWritableDatabase().execSQL("INSERT INTO song_play_count "
                    + "(song_id, week_index, week0, play_count_score) VALUES (?, ?, ?, ?)",
                    new Object[] {42L, week - 1, 3, 225});
            store.bumpPlayCount(42);
            try (Cursor cursor = store.getReadableDatabase().rawQuery(
                    "SELECT week0, week1 FROM song_play_count WHERE song_id=42", null)) {
                assertTrue(cursor.moveToFirst());
                assertEquals(1, cursor.getInt(0));
                assertEquals(3, cursor.getInt(1));
            }
        }
    }

    private void assertMissingColumn(String column, String[] columns, Object[] values) {
        try (SongPlayCountStore store = new SongPlayCountStore(RuntimeEnvironment.getApplication());
             MatrixCursor cursor = new MatrixCursor(columns)) {
            cursor.addRow(values);
            cursor.moveToFirst();
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> ReflectionHelpers.callInstanceMethod(store, "updateExistingRow",
                            ReflectionHelpers.ClassParameter.from(SQLiteDatabase.class, store.getWritableDatabase()),
                            ReflectionHelpers.ClassParameter.from(Cursor.class, cursor),
                            ReflectionHelpers.ClassParameter.from(boolean.class, true)));
            assertTrue(error.getMessage().contains(column));
        }
    }
}
