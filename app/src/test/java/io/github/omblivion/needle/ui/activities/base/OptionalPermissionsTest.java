package io.github.omblivion.needle.ui.activities.base;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Application;

import io.github.omblivion.needle.App;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = App.class)
public class OptionalPermissionsTest {
    public static class PermissionActivity extends AbsMusicServiceActivity {}

    @Test
    public void audioAccessDoesNotRequireImagesOrNotifications() {
        PermissionActivity activity = Robolectric.buildActivity(PermissionActivity.class).get();
        ReflectionHelpers.setField(activity, "permissions", activity.getPermissionsToRequest());
        shadowOf((Application) RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.READ_MEDIA_AUDIO);
        assertTrue(activity.hasPermissions());
    }

    @Test
    public void imagesAndNotificationsDoNotReplaceAudioPermission() {
        PermissionActivity activity = Robolectric.buildActivity(PermissionActivity.class).get();
        ReflectionHelpers.setField(activity, "permissions", activity.getPermissionsToRequest());
        shadowOf((Application) RuntimeEnvironment.getApplication()).grantPermissions(
                Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.POST_NOTIFICATIONS);
        assertFalse(activity.hasPermissions());
    }
}
