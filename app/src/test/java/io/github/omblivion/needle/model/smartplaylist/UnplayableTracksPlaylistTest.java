package io.github.omblivion.needle.model.smartplaylist;

import static org.junit.Assert.*;

import android.content.Context;
import android.os.Parcel;

import io.github.omblivion.needle.App;
import io.github.omblivion.needle.model.Playlist;
import io.github.omblivion.needle.model.Song;
import io.github.omblivion.needle.provider.UnplayableTrackStore;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = App.class)
public class UnplayableTracksPlaylistTest {
    private Context context;
    private UnplayableTrackStore store;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        store = UnplayableTrackStore.getInstance(context);
        store.getWritableDatabase().delete("reports", null, null);
    }

    @Test
    public void playlistShowsStoredFilesEvenWhenTheyAreNotInDiscography() {
        Song song = new Song(42, "Broken", 0, 0, 20000, "/music/broken.mp3",
                0, 0, 1, "Album", List.of("Artist"));
        store.recordFailure(song);
        UnplayableTracksPlaylist playlist = new UnplayableTracksPlaylist(context);
        assertEquals(List.of(song), playlist.getSongs(context));
        assertFalse(playlist.isClearable());
        assertFalse(playlist.canImport());
    }

    @Test
    public void playlistSurvivesNavigationParcelRoundTrip() {
        Parcel parcel = Parcel.obtain();
        try {
            UnplayableTracksPlaylist original = new UnplayableTracksPlaylist(context);
            original.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            UnplayableTracksPlaylist restored = UnplayableTracksPlaylist.CREATOR.createFromParcel(parcel);
            assertEquals(original, restored);
            assertEquals(original.getSongs(context), restored.getSongs(context));
        } finally {
            parcel.recycle();
        }
    }

    @Test
    public void libraryPlaylistListIncludesTheMaintenancePlaylistEvenWhenEmpty() throws Exception {
        Class<?> loader = Class.forName("io.github.omblivion.needle.ui.fragments.mainactivity.library.pager.PlaylistsFragment$AsyncPlaylistLoader");
        ArrayList<Playlist> playlists = ReflectionHelpers.callStaticMethod(loader, "getAllPlaylists",
                ReflectionHelpers.ClassParameter.from(Context.class, context));
        assertEquals(1, playlists.stream().filter(p -> p instanceof UnplayableTracksPlaylist).count());
    }
}
