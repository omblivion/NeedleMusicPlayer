package io.github.omblivion.needle.preferences;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import io.github.omblivion.needle.App;
import io.github.omblivion.needle.model.TopTracksWindow;
import io.github.omblivion.needle.util.PreferenceUtil;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = App.class)
public class TopTracksWindowPreferenceTest {
    private SharedPreferences preferences;

    @Before
    public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        preferences = PreferenceManager.getDefaultSharedPreferences(context);
        preferences.edit().clear().commit();
        ReflectionHelpers.setStaticField(PreferenceUtil.class, "sInstance", null);
    }

    @Test
    public void absentPreferenceReadsBackAs52Weeks() {
        assertEquals(TopTracksWindow.WEEKS_52, PreferenceUtil.getInstance().getTopTracksWindow());
    }

    @Test
    public void aStoredPresetRoundTrips() {
        PreferenceUtil.getInstance().setTopTracksWindow(TopTracksWindow.DAYS_30);

        assertEquals(TopTracksWindow.DAYS_30, PreferenceUtil.getInstance().getTopTracksWindow());
        assertEquals("30d", preferences.getString(PreferenceUtil.TOP_TRACKS_WINDOW, null));
    }

    @Test
    public void anInvalidStoredValueFallsBackTo52Weeks() {
        preferences.edit().putString(PreferenceUtil.TOP_TRACKS_WINDOW, "3 parsecs").commit();

        assertEquals(TopTracksWindow.WEEKS_52, PreferenceUtil.getInstance().getTopTracksWindow());
    }
}
