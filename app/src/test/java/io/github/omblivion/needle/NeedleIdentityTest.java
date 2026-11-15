package io.github.omblivion.needle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Locale;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = App.class)
public class NeedleIdentityTest {
    @Test
    public void applicationAndProviderCanCoexistWithVinyl() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        assertNotEquals("com." + "poupa.vinylmusicplayer.debug", context.getPackageName());
        assertTrue(context.getPackageName().startsWith("io.github.omblivion.needle"));
        PackageInfo info = context.getPackageManager().getPackageInfo(
                context.getPackageName(), PackageManager.GET_PROVIDERS | PackageManager.GET_ACTIVITIES);
        assertTrue(java.util.Arrays.stream(info.providers)
                .anyMatch(provider -> provider.authority.equals(context.getPackageName())));
        assertTrue(java.util.Arrays.stream(info.activities)
                .anyMatch(activity -> activity.name.equals("io.github.omblivion.needle.ui.activities.MainActivity")));
        // Third-party libraries legitimately contribute activities in their own namespaces.
        assertFalse(java.util.Arrays.stream(info.activities)
                .anyMatch(activity -> activity.name.startsWith("com." + "poupa.vinylmusicplayer.")));
    }

    @Test
    public void launcherAndWidgetLabelsUseNeedleInEnglishAndItalian() {
        Context context = RuntimeEnvironment.getApplication();
        for (String language : new String[] {"en", "it"}) {
            Configuration config = new Configuration(context.getResources().getConfiguration());
            config.setLocale(Locale.forLanguageTag(language));
            Context localized = context.createConfigurationContext(config);
            assertEquals(BuildConfig.DEBUG ? "Needle Music Player DEBUG" : "Needle Music Player",
                    localized.getString(R.string.app_name));
            assertEquals(BuildConfig.DEBUG ? "Needle DEBUG" : "Needle",
                    localized.getString(R.string.app_short_name));
            assertTrue(localized.getString(R.string.app_widget_big_name).startsWith("Needle Music Player"));
        }
    }
}
