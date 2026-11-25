package io.github.omblivion.needle.provider;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import android.database.Cursor;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteDatabase;

import io.github.omblivion.needle.App;
import io.github.omblivion.needle.misc.queue.IndexedSong;
import io.github.omblivion.needle.model.Song;

import org.junit.Test;
import org.junit.Before;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import io.github.omblivion.needle.discog.Discography;

import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = App.class)
public class QueuePersistenceTest {
    @org.junit.After public void clearAuxiliarySingletons() {
        ReflectionHelpers.setStaticField(HistoryStore.class, "sInstance", null);
        ReflectionHelpers.setStaticField(SongPlayCountStore.class, "sInstance", null);
        ReflectionHelpers.setStaticField(PreferencesBackedSongList.class, "preferences", null);
        ReflectionHelpers.setStaticField(UnplayableTrackStore.class, "instance", null);
    }
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

    @Test
    public void restoreDoesNotShiftSongsWhenMiddleEntryIsUnavailableDuringRefresh() {
        MusicPlaybackQueueStore store = MusicPlaybackQueueStore.getInstance(RuntimeEnvironment.getApplication());
        ArrayList<IndexedSong> songs = queue(1);
        songs.add(new IndexedSong(song(2, "/Music/b.mp3"), 1, 1));
        songs.add(new IndexedSong(song(3, "/Music/c.mp3"), 2, 2));
        songs.add(new IndexedSong(song(3, "/Music/c.mp3"), 3, 3));
        store.saveQueues(songs, songs);
        addToCatalog(songs.get(0));
        addToCatalog(songs.get(2));
        // Even a resetting catalog must not pair filtered-list offsets with queue rows.
        ReflectionHelpers.callInstanceMethod(Discography.getInstance(), "setCacheState",
                ReflectionHelpers.ClassParameter.from(consistencyClass(), resetting()));
        ArrayList<IndexedSong> restored = store.getSavedPlayingQueue();
        assertEquals(4, restored.size());
        assertEquals(1, restored.get(0).id);
        assertEquals(2, restored.get(1).id);
        assertEquals("/Music/b.mp3", restored.get(1).data);
        assertEquals(3, restored.get(2).id);
        assertEquals(3, restored.get(3).id);
        assertEquals(2, restored.get(2).index);
    }

    @Test
    public void restoredQueueKeepsStoredIdentityWhenIdWasReused() {
        MusicPlaybackQueueStore store = MusicPlaybackQueueStore.getInstance(RuntimeEnvironment.getApplication());
        store.saveQueues(queue(7), queue(7));
        addToCatalog(song(7, "/Music/unrelated.mp3"));
        assertEquals("/Music/a.mp3", store.getSavedPlayingQueue().get(0).data);
    }

    @Test
    public void versionSevenQueueMigrationKeepsOrderDuplicatesAndUnknownLocators() {
        try (SQLiteDatabase database = RuntimeEnvironment.getApplication()
                .openOrCreateDatabase("music_playback_state.db", 0, null)) {
            for (String table : List.of("playing_queue", "original_playing_queue")) {
                database.execSQL("CREATE TABLE " + table + " (_id LONG NOT NULL, index_in_queue INT NOT NULL)");
                database.execSQL("INSERT INTO " + table + " VALUES (7, 0), (7, 1)");
            }
            database.setVersion(7);
        }
        MusicPlaybackQueueStore store = MusicPlaybackQueueStore.getInstance(RuntimeEnvironment.getApplication());
        try (Cursor cursor = store.getReadableDatabase().rawQuery(
                "SELECT _id,index_in_queue,expected_path,expected_uri FROM playing_queue ORDER BY rowid", null)) {
            assertEquals(8, store.getReadableDatabase().getVersion());
            assertEquals(2, cursor.getCount());
            cursor.moveToFirst();
            assertEquals(7, cursor.getLong(0));
            assertEquals("", cursor.getString(2));
            assertEquals("", cursor.getString(3));
            cursor.moveToNext();
            assertEquals(7, cursor.getLong(0));
            assertEquals(1, cursor.getInt(1));
        }
        addToCatalog(song(7, "/Music/new-owner.mp3"));
        assertEquals("", store.getSavedPlayingQueue().get(0).data);
    }

    @Test public void coldQueueRestoreLoadsCommittedCatalogBeforeResolvingEntries() {
        MusicPlaybackQueueStore store = MusicPlaybackQueueStore.getInstance(RuntimeEnvironment.getApplication());
        Song a = song(1, "/Music/a.mp3");
        a.title = "Committed title";
        App.getDiscography().publishSnapshot(new io.github.omblivion.needle.discog.LibrarySnapshot(List.of(a), 0));
        ArrayList<IndexedSong> songs = new ArrayList<>(List.of(new IndexedSong(a, 0, 0)));
        store.saveQueues(songs, songs);
        ReflectionHelpers.setStaticField(App.class, "discography", new Discography());
        // The service loads the committed catalog before resolving restored entries.
        App.getDiscography().ensureLoaded();
        assertEquals("Committed title", store.getSavedPlayingQueue().get(0).title);
    }

    @Test public void deletingMiddleSavedEntryPreservesRestorableIndexesAndCurrentPosition() {
        MusicPlaybackQueueStore store = MusicPlaybackQueueStore.getInstance(RuntimeEnvironment.getApplication());
        Song a = song(1, "/Music/a.mp3"), b = song(2, "/Music/b.mp3"), c = song(3, "/Music/c.mp3");
        App.getDiscography().publishSnapshot(new io.github.omblivion.needle.discog.LibrarySnapshot(List.of(a,b,c), 0));
        ArrayList<IndexedSong> songs = new ArrayList<>(List.of(new IndexedSong(a,0,0),
                new IndexedSong(b,1,1), new IndexedSong(c,2,2)));
        store.saveQueues(songs, songs);
        App.getDiscography().confirmDeletion(List.of(b));
        io.github.omblivion.needle.misc.queue.StaticPlayingQueue restored =
                new io.github.omblivion.needle.misc.queue.StaticPlayingQueue(store.getSavedPlayingQueue(),
                        store.getSavedOriginalPlayingQueue(), 1, 0, 0);
        assertEquals(2, restored.size());
        assertEquals(1, restored.getCurrentPosition());
        assertEquals(3, restored.getPlayingQueue().get(1).id);
        assertEquals(1, restored.getPlayingQueue().get(1).index);
    }

    private static Song song(long id, String path) {
        return new Song(id, "Track", 1, 2024, 1000, path, 0, 0, 1,
                "Album", new ArrayList<>(List.of("Artist")));
    }

    private static void addToCatalog(Song song) {
        ReflectionHelpers.callInstanceMethod(Discography.getInstance(), "addSong",
                ReflectionHelpers.ClassParameter.from(Song.class, song),
                ReflectionHelpers.ClassParameter.from(boolean.class, true));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Class consistencyClass() {
        try {
            return Class.forName("io.github.omblivion.needle.discog.MemCache$ConsistencyState");
        } catch (ClassNotFoundException e) {
            throw new AssertionError(e);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object resetting() {
        return Enum.valueOf(consistencyClass(), "RESETTING");
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
