package com.poupa.vinylmusicplayer.provider;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import android.database.Cursor;
import android.database.sqlite.SQLiteException;

import com.poupa.vinylmusicplayer.App;
import com.poupa.vinylmusicplayer.misc.queue.IndexedSong;
import com.poupa.vinylmusicplayer.model.Song;

import org.junit.Test;
import org.junit.Before;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = App.class)
public class QueuePersistenceTest {
    @Before
    public void resetStore() {
        ReflectionHelpers.setStaticField(MusicPlaybackQueueStore.class, "sInstance", null);
    }

    @Test
    public void failureSavingOriginalQueueRollsBackBothQueues() {
        MusicPlaybackQueueStore store = MusicPlaybackQueueStore.getInstance(RuntimeEnvironment.getApplication());
        store.saveQueues(queue(1), queue(1));
        store.getWritableDatabase().execSQL("CREATE TRIGGER fail_original BEFORE INSERT ON original_playing_queue "
                + "BEGIN SELECT RAISE(ABORT, 'Simulated storage failure'); END");

        assertThrows(SQLiteException.class, () -> store.saveQueues(queue(2), queue(2)));
        assertSavedId(store, "playing_queue", 1);
        assertSavedId(store, "original_playing_queue", 1);
    }

    @Test
    public void successfulSavePreservesOrderAndDuplicates() {
        MusicPlaybackQueueStore store = MusicPlaybackQueueStore.getInstance(RuntimeEnvironment.getApplication());
        ArrayList<IndexedSong> songs = queue(7);
        songs.add(new IndexedSong(songs.get(0), 1, 1));
        store.saveQueues(songs, songs);
        try (Cursor cursor = store.getReadableDatabase().rawQuery(
                "SELECT _id, index_in_queue FROM playing_queue ORDER BY rowid", null)) {
            assertEquals(2, cursor.getCount());
            cursor.moveToFirst();
            assertEquals(7, cursor.getLong(0));
            assertEquals(0, cursor.getInt(1));
            cursor.moveToNext();
            assertEquals(7, cursor.getLong(0));
            assertEquals(1, cursor.getInt(1));
        }
    }

    private static ArrayList<IndexedSong> queue(long id) {
        Song song = new Song(id, "Track", 1, 2024, 1000, "/Music/a.mp3",
                0, 0, 1, "Album", List.of("Artist"));
        return new ArrayList<>(List.of(new IndexedSong(song, 0, 0)));
    }

    private static void assertSavedId(MusicPlaybackQueueStore store, String table, long expected) {
        try (Cursor cursor = store.getReadableDatabase().rawQuery("SELECT _id FROM " + table, null)) {
            assertEquals(1, cursor.getCount());
            cursor.moveToFirst();
            assertEquals(expected, cursor.getLong(0));
        }
    }
}
