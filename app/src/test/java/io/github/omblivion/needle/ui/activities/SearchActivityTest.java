package io.github.omblivion.needle.ui.activities;

import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertEquals;

import android.content.ComponentName;
import android.os.Looper;

import androidx.loader.app.LoaderManager;

import io.github.omblivion.needle.App;
import io.github.omblivion.needle.interfaces.LoaderIds;
import io.github.omblivion.needle.service.MusicService;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.time.Duration;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = App.class)
public class SearchActivityTest {
    @Before public void leavePlaybackServiceUnbound() {
        var app = RuntimeEnvironment.getApplication();
        Shadows.shadowOf(app).declareComponentUnbindable(new ComponentName(app, MusicService.class));
    }

    @Test public void mediaIndexNotificationsDoNotRestartSearchOfThePublishedCatalog() {
        try (var controller = Robolectric.buildActivity(SearchActivity.class).create()) {
            SearchActivity activity = controller.get();
            LoaderManager loaders = LoaderManager.getInstance(activity);
            var initial = loaders.getLoader(LoaderIds.SEARCH_ACTIVITY);
            activity.onMediaStoreChanged();
            activity.onMediaStoreChanged();
            assertSame(initial, loaders.getLoader(LoaderIds.SEARCH_ACTIVITY));
        }
    }

    @Test public void typingSeveralLettersDoesNotLaunchWorkForEveryPrefix() {
        try (var controller = Robolectric.buildActivity(SearchActivity.class).create()) {
            SearchActivity activity = controller.get();
            LoaderManager loaders = LoaderManager.getInstance(activity);
            var initial = loaders.getLoader(LoaderIds.SEARCH_ACTIVITY);
            activity.onQueryTextChange("S");
            activity.onQueryTextChange("Sc");
            activity.onQueryTextChange("Scat");
            assertSame(initial, loaders.getLoader(LoaderIds.SEARCH_ACTIVITY));
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));
            assertNotSame(initial, loaders.getLoader(LoaderIds.SEARCH_ACTIVITY));
        }
    }

    @Test public void rotatingDuringDebounceRestartsTheRetainedLoaderWithTheLatestQuery() {
        try (var controller = Robolectric.buildActivity(SearchActivity.class).setup()) {
            controller.get().onQueryTextSubmit("Sc");
            controller.get().onQueryTextChange("Scat");
            controller.recreate();
            var loader = LoaderManager.getInstance(controller.get()).getLoader(LoaderIds.SEARCH_ACTIVITY);
            assertEquals("Scat", ReflectionHelpers.getField(loader, "query"));
        }
    }
}
