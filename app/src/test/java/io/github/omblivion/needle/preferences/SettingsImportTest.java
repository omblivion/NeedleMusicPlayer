package io.github.omblivion.needle.preferences;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import androidx.preference.PreferenceManager;

import io.github.omblivion.needle.App;
import io.github.omblivion.needle.util.PreferenceUtil;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = App.class)
public class SettingsImportTest {
    private SharedPreferences preferences;
    private SharedPreferencesImporter importer;

    @Before
    public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        preferences = PreferenceManager.getDefaultSharedPreferences(context);
        preferences.edit().clear().commit();
        importer = new SharedPreferencesImporter();
        ReflectionHelpers.setField(importer, "context", context);
        ReflectionHelpers.setField(importer, "sharedPreferences", preferences);
    }

    @Test
    public void restoresDecimalAndWholeNumberReplayGainAsFloats() throws Exception {
        importJson("{\"file_format\":\"json\",\"version_code\":202,"
                + "\"replaygain_preamp_with_tag\":1.5,\"replaygain_preamp_without_tag\":2}");
        assertEquals(1.5f, preferences.getFloat(PreferenceUtil.RG_PREAMP_WITH_TAG, 0), 0);
        assertEquals(2f, preferences.getFloat(PreferenceUtil.RG_PREAMP_WITHOUT_TAG, 0), 0);
    }

    @Test
    public void malformedJsonLeavesPreferencesUnchanged() throws Exception {
        preferences.edit().putInt(PreferenceUtil.PRIMARY_COLOR, 123).commit();
        importJson("{broken");
        assertEquals(123, preferences.getInt(PreferenceUtil.PRIMARY_COLOR, 0));
    }

    @Test
    public void missingMetadataLeavesPreferencesUnchanged() throws Exception {
        preferences.edit().putInt(PreferenceUtil.PRIMARY_COLOR, 123).commit();
        importJson("{\"primary_color\":456}");
        assertEquals(123, preferences.getInt(PreferenceUtil.PRIMARY_COLOR, 0));
    }

    @Test
    public void invalidTypeDoesNotPartiallyApplyBackup() throws Exception {
        preferences.edit().putInt(PreferenceUtil.PRIMARY_COLOR, 123).commit();
        importJson("{\"file_format\":\"json\",\"version_code\":202,"
                + "\"primary_color\":456,\"replaygain_preamp_with_tag\":\"wrong\"}");
        assertEquals(123, preferences.getInt(PreferenceUtil.PRIMARY_COLOR, 0));
        assertFalse(preferences.contains(PreferenceUtil.RG_PREAMP_WITH_TAG));
    }

    @Test
    public void doesNotImportPrivateOrUnknownKeys() throws Exception {
        importJson("{\"file_format\":\"json\",\"version_code\":202,"
                + "\"saf_sdcard_uri\":\"content://other-app\",\"unknown_key\":true}");
        assertFalse(preferences.contains("saf_sdcard_uri"));
        assertFalse(preferences.contains("unknown_key"));
    }

    @Test
    public void invalidStructuredCategoriesRejectEntireBackup() throws Exception {
        for (String categories : new String[] {"null", "[null]",
                "[{\"category\":\"UNKNOWN\",\"visible\":true}]",
                "[{\"category\":\"SONGS\",\"visible\":\"true\"}]"}) {
            preferences.edit().clear().putInt(PreferenceUtil.PRIMARY_COLOR, 123).commit();
            importJson("{\"file_format\":\"json\",\"version_code\":202,\"primary_color\":456,"
                    + "\"library_categories\":" + new com.google.gson.Gson().toJson(categories) + "}");
            assertEquals(categories, 123, preferences.getInt(PreferenceUtil.PRIMARY_COLOR, 0));
            assertFalse(preferences.contains(PreferenceUtil.LIBRARY_CATEGORIES));
        }
    }

    @Test
    public void existingMalformedCategoriesFallBackToDefaults() {
        ReflectionHelpers.setStaticField(PreferenceUtil.class, "sInstance", null);
        preferences.edit().putString(PreferenceUtil.LIBRARY_CATEGORIES, "null").commit();
        assertNotNull(PreferenceUtil.getInstance().getLibraryCategoryInfos());
        assertEquals(5, PreferenceUtil.getInstance().getLibraryCategoryInfos().size());
    }

    @Test
    public void invalidGridSizeRejectsEntireBackup() throws Exception {
        for (int columns : new int[] {0, -1, 9, Integer.MAX_VALUE}) {
            preferences.edit().clear().putInt(PreferenceUtil.PRIMARY_COLOR, 123).commit();
            importJson("{\"file_format\":\"json\",\"version_code\":202,\"primary_color\":456,"
                    + "\"album_grid_size\":" + columns + "}");
            assertEquals(123, preferences.getInt(PreferenceUtil.PRIMARY_COLOR, 0));
            assertFalse(preferences.contains("album_grid_size"));
        }
    }

    @Test
    public void validCategoryOrderAndVisibilityArePreserved() throws Exception {
        ReflectionHelpers.setStaticField(PreferenceUtil.class, "sInstance", null);
        String categories = "[{\"category\":\"ALBUMS\",\"visible\":false},"
                + "{\"category\":\"SONGS\",\"visible\":true}]";
        importJson("{\"file_format\":\"json\",\"version_code\":202,\"library_categories\":"
                + new com.google.gson.Gson().toJson(categories) + "}");
        assertEquals(io.github.omblivion.needle.model.CategoryInfo.Category.ALBUMS,
                PreferenceUtil.getInstance().getLibraryCategoryInfos().get(0).category);
        assertFalse(PreferenceUtil.getInstance().getLibraryCategoryInfos().get(0).visible);
        assertEquals(2, PreferenceUtil.getInstance().getLibraryCategoryInfos().size());
    }

    private void importJson(String json) throws Exception {
        File file = File.createTempFile("settings", ".json", RuntimeEnvironment.getApplication().getCacheDir());
        Files.write(file.toPath(), json.getBytes(StandardCharsets.UTF_8));
        ReflectionHelpers.callInstanceMethod(importer, "importSettings",
                ReflectionHelpers.ClassParameter.from(Uri.class, Uri.fromFile(file)));
    }
}
