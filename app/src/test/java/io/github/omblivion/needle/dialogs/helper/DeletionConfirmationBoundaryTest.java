package io.github.omblivion.needle.dialogs.helper;

import static org.junit.Assert.*;

import android.app.PendingIntent;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.Looper;
import android.provider.MediaStore;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContract;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.app.ActivityOptionsCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import io.github.omblivion.needle.App;
import io.github.omblivion.needle.model.Song;
import io.github.omblivion.needle.provider.UnplayableTrackStore;
import io.github.omblivion.needle.util.MusicUtil;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowContentResolver;

import java.util.Collection;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = App.class, shadows = DeletionConfirmationBoundaryTest.ConfirmationShadow.class)
public class DeletionConfirmationBoundaryTest {
    /** Replace only the external Android confirmation UI, not the media database. */
    @Implements(MediaStore.class)
    public static class ConfirmationShadow {
        @Implementation
        protected static PendingIntent createDeleteRequest(ContentResolver resolver, Collection<Uri> uris) {
            return PendingIntent.getActivity(RuntimeEnvironment.getApplication(), 0,
                    new Intent("test.confirm.delete"), PendingIntent.FLAG_IMMUTABLE);
        }
    }

    public static class MediaProvider extends ContentProvider {
        SQLiteDatabase db;
        final CountDownLatch queried = new CountDownLatch(1);
        volatile boolean queriedOnMainThread;
        @Override public boolean onCreate() {
            db = SQLiteDatabase.create(null);
            db.execSQL("CREATE TABLE audio (_id INTEGER PRIMARY KEY, _data TEXT)");
            return true;
        }
        @Override public Cursor query(Uri uri, String[] projection, String selection,
                                      String[] args, String sort) {
            queriedOnMainThread = Looper.myLooper() == Looper.getMainLooper();
            queried.countDown();
            if (uri.getLastPathSegment().matches("\\d+")) {
                selection = "_id = ?";
                args = new String[]{uri.getLastPathSegment()};
            }
            return db.query("audio", projection, selection, args, null, null, sort);
        }
        @Override public Cursor query(Uri uri, String[] projection, String selection,
                                      String[] args, String sort, CancellationSignal signal) {
            return query(uri, projection, selection, args, sort);
        }
        @Override public int delete(Uri uri, String selection, String[] args) {
            return db.delete("audio", selection, args);
        }
        @Override public String getType(Uri uri) {return "audio/mpeg";}
        @Override public Uri insert(Uri uri, ContentValues values) {
            db.insertOrThrow("audio", null, values);
            return uri;
        }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
            return db.update("audio", values, selection, args);
        }
        int rowCount() {
            try (Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM audio", null)) {
                cursor.moveToFirst();
                return cursor.getInt(0);
            }
        }
    }

    private static class ConfirmationLauncher extends ActivityResultLauncher<IntentSenderRequest> {
        IntentSenderRequest request;
        @Override public void launch(IntentSenderRequest input, ActivityOptionsCompat options) {request = input;}
        @Override public void unregister() {}
        @Override public ActivityResultContract<IntentSenderRequest, ?> getContract() {
            return new ActivityResultContracts.StartIntentSenderForResult();
        }
    }

    private MediaProvider provider;
    private Song song;
    private UnplayableTrackStore store;

    @Before
    public void setUp() {
        provider = new MediaProvider();
        provider.attachInfo(RuntimeEnvironment.getApplication(), null);
        ShadowContentResolver.registerProviderInternal("media", provider);
        ContentValues values = new ContentValues();
        values.put("_id", 42);
        values.put("_data", "/music/broken.mp3");
        provider.db.insertOrThrow("audio", null, values);
        song = new Song(42, "Broken", 0, 0, 20000, "/music/broken.mp3",
                0, 0, 1, "Album", List.of("Artist"));
        store = UnplayableTrackStore.getInstance(RuntimeEnvironment.getApplication());
        store.getWritableDatabase().delete("reports", null, null);
        store.recordFailure(song);
    }

    private ConfirmationLauncher requestDeletion() {
        ConfirmationLauncher launcher = new ConfirmationLauncher();
        try (var controller = Robolectric.buildActivity(FragmentActivity.class).setup()) {
            Fragment fragment = new Fragment();
            controller.get().getSupportFragmentManager().beginTransaction().add(fragment, "delete").commitNow();
            MusicUtil.deleteTracks(fragment, launcher, List.of(song), null);
        }
        return launcher;
    }

    @Test
    public void askingForConfirmationDoesNotDeleteMediaOrDiscardTheReport() {
        ConfirmationLauncher launcher = requestDeletion();
        assertNotNull(launcher.request);
        assertEquals(1, provider.rowCount());
        assertTrue(store.contains(song));
    }

    @Test
    public void staleMediaIdCannotDeleteAReplacementFile() {
        ContentValues values = new ContentValues();
        values.put("_data", "/music/replacement.mp3");
        provider.db.update("audio", values, "_id = 42", null);
        ConfirmationLauncher launcher = requestDeletion();
        assertNull(launcher.request);
        assertEquals(1, provider.rowCount());
        assertTrue(store.contains(song));
    }

    @Test
    public void unavailableMediaDoesNotDiscardTheReport() {
        provider.db.delete("audio", null, null);
        assertNull(requestDeletion().request);
        assertTrue(store.contains(song));
    }

    @Test
    public void confirmationPreparationDoesNotQueryMediaOnTheUiThread() throws Exception {
        try (var controller = Robolectric.buildActivity(FragmentActivity.class).setup()) {
            DeleteSongsDialogApi30 fragment = DeleteSongsDialogApi30.create(new ArrayList<>(List.of(song)));
            controller.get().getSupportFragmentManager().beginTransaction().add(fragment, "delete").commitNow();
            assertTrue("Media identity must be checked before requesting deletion",
                    provider.queried.await(10, TimeUnit.SECONDS));
            assertFalse("Media queries must not block the UI thread", provider.queriedOnMainThread);
            assertTrue(store.contains(song));
        }
    }
}
