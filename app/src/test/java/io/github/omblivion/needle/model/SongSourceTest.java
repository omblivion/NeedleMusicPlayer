package io.github.omblivion.needle.model;

import static org.junit.Assert.*;

import android.os.Parcel;

import io.github.omblivion.needle.App;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = App.class)
public class SongSourceTest {
    @Test
    public void reusedIdAtAnotherPathDoesNotMatch() {
        assertFalse(SongSource.from(song("/Music/a.mp3"))
                .sameTrack(SongSource.from(song("/Music/b.mp3"))));
    }

    @Test
    public void sameTrackIgnoresProviderVolumeAndId() {
        Song a = song("/Music/a.mp3");
        Song b = new Song(a, 99);
        b.contentUri = "content://media/0123-4567/audio/media/99";
        assertTrue(SongSource.from(a).sameTrack(SongSource.from(b)));
    }

    @Test
    public void changedSizeOrGenerationInvalidatesVersion() {
        Song a = song("/Music/a.mp3");
        Song b = new Song(a);
        b.sourceSize = 201;
        assertTrue(SongSource.from(a).versionChanged(SongSource.from(b)));
        b.sourceSize = a.sourceSize;
        b.sourceGeneration = 101;
        assertTrue(SongSource.from(a).versionChanged(SongSource.from(b)));
        b.sourceGeneration = a.sourceGeneration;
        b.providerVersion = "new-provider";
        assertTrue(SongSource.from(a).versionChanged(SongSource.from(b)));
    }

    @Test
    public void versionChangedTreatsAKnownProviderSizeAsAuthoritative() {
        Song a = song("/Music/a.mp3");   // size 200, generation 100
        Song unknown = new Song(a);
        unknown.sourceSize = -1;
        unknown.sourceGeneration = -1;
        unknown.providerVersion = "";
        // A catalog that never knew the size cannot be assumed current once the
        // provider reports one.
        assertTrue(SongSource.from(unknown).versionChanged(SongSource.from(a)));
        // Two unknowns never count as a change.
        assertFalse(SongSource.from(unknown).versionChanged(SongSource.from(unknown)));
    }

    @Test
    public void locatorCanonicalizesSegmentsButDoesNotFoldCaseOrUnicode() {
        Song a = song("/Music/album/../Été.mp3");
        Song b = song("/Music/Été.mp3");
        assertTrue(SongSource.from(a).sameTrack(SongSource.from(b)));
        assertFalse(SongSource.from(a).sameTrack(SongSource.from(song("/Music/été.mp3"))));
    }

    @Test
    public void sourceSnapshotDoesNotChangeWithSong() {
        Song a = song("/Music/a.mp3");
        SongSource source = SongSource.from(a);
        a.sourceSize = 999;
        assertTrue(source.versionChanged(SongSource.from(a)));
    }

    @Test
    public void copyRetainsSourceAndDoesNotAliasTagLists() {
        Song a = song("/Music/a.mp3");
        a.albumArtistNames.add("Album artist");
        a.genres.add("Rock");
        Song b = new Song(a);
        assertFalse(SongSource.from(a).versionChanged(SongSource.from(b)));
        b.artistNames.clear();
        b.albumArtistNames.clear();
        b.genres.clear();
        assertEquals(List.of("Artist"), a.artistNames);
        assertEquals(List.of("Album artist"), a.albumArtistNames);
        assertEquals(List.of("Rock"), a.genres);
    }

    @Test
    public void parcelRetainsSourceAndRetryFlag() {
        Song a = song("/Music/a.mp3");
        a.metadataRetryNeeded = true;
        Parcel parcel = Parcel.obtain();
        try {
            a.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            Song b = Song.CREATOR.createFromParcel(parcel);
            assertEquals("content://media/external_primary/audio/media/42", b.contentUri);
            assertEquals(200, b.sourceSize);
            assertEquals(100, b.sourceGeneration);
            assertEquals("provider-v1", b.providerVersion);
            assertTrue(b.metadataRetryNeeded);
        } finally {
            parcel.recycle();
        }
    }

    @Test
    public void songSourceExposesOnlyTheTwoIdentityPredicates() {
        // The one identity rule is the canonical path; version fields only
        // invalidate caches. Any extra predicate invites a second rule.
        for (java.lang.reflect.Method method : SongSource.class.getDeclaredMethods()) {
            String name = method.getName();
            if (!java.lang.reflect.Modifier.isPublic(method.getModifiers())) continue;
            assertTrue("Unexpected predicate " + name, java.util.Set.of(
                    "from", "sameTrack", "versionChanged", "isKnown", "canonicalPath").contains(name));
        }
    }

    private static Song song(String path) {
        Song song = new Song(42, "Same title", 1, 2024, 1000, path, 1, 2, 1,
                "Album", new ArrayList<>(List.of("Artist")));
        song.contentUri = "content://media/external_primary/audio/media/42";
        song.sourceSize = 200;
        song.sourceGeneration = 100;
        song.providerVersion = "provider-v1";
        return song;
    }
}
