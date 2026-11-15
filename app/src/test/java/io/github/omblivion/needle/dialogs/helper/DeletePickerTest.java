package io.github.omblivion.needle.dialogs.helper;

import static org.junit.Assert.assertNull;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.net.Uri;

import androidx.activity.result.ActivityResultLauncher;
import androidx.fragment.app.FragmentActivity;

import io.github.omblivion.needle.App;
import io.github.omblivion.needle.model.Song;
import io.github.omblivion.needle.util.PreferenceUtil;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = App.class)
public class DeletePickerTest {
    @Before public void resetPreferences() {
        ReflectionHelpers.setStaticField(PreferenceUtil.class, "sInstance", null);
    }

    @Test
    public void cancellingTreePickerDoesNotStartDeleteTask() {
        try (org.robolectric.android.controller.ActivityController<FragmentActivity> controller =
                     Robolectric.buildActivity(FragmentActivity.class).setup()) {
            FragmentActivity activity = controller.get();
            DeleteSongsDialogApi19 dialog = DeleteSongsDialogApi19.create(Song.EMPTY_SONG);
            activity.getSupportFragmentManager().beginTransaction().add(dialog, "delete").commitNow();
            ActivityResultLauncher<Uri> launcher = ReflectionHelpers.getField(dialog, "deleteSongs_SAFTreePicker");
            launcher.launch(null);
            ShadowActivity.IntentForResult request = shadowOf(activity).getNextStartedActivityForResult();
            activity.getActivityResultRegistry().dispatchResult(request.requestCode, Activity.RESULT_CANCELED, null);
            assertNull(ReflectionHelpers.getField(dialog, "deleteSongsTask"));
        }
    }

    @Test
    public void cancellingLegacyFilePickerDoesNotStartDeleteTask() {
        try (org.robolectric.android.controller.ActivityController<FragmentActivity> controller =
                     Robolectric.buildActivity(FragmentActivity.class).setup()) {
            FragmentActivity activity = controller.get();
            DeleteSongsDialogApi19 dialog = DeleteSongsDialogApi19.create(Song.EMPTY_SONG);
            activity.getSupportFragmentManager().beginTransaction().add(dialog, "delete").commitNow();
            ActivityResultLauncher<String> launcher = ReflectionHelpers.getField(dialog, "deleteSongsApi19_SAFFilePicker");
            launcher.launch("track.mp3");
            ShadowActivity.IntentForResult request = shadowOf(activity).getNextStartedActivityForResult();
            activity.getActivityResultRegistry().dispatchResult(request.requestCode, Activity.RESULT_CANCELED, null);
            assertNull(ReflectionHelpers.getField(dialog, "deleteSongsTask"));
        }
    }

    @Test
    public void legacySdkBranchDoesNotRegisterTreePicker() {
        int sdk = android.os.Build.VERSION.SDK_INT;
        ReflectionHelpers.setStaticField(android.os.Build.VERSION.class, "SDK_INT", 19);
        try {
            try (org.robolectric.android.controller.ActivityController<FragmentActivity> controller =
                         Robolectric.buildActivity(FragmentActivity.class).setup()) {
                DeleteSongsDialogApi19 dialog = DeleteSongsDialogApi19.create(Song.EMPTY_SONG);
                controller.get().getSupportFragmentManager().beginTransaction().add(dialog, "delete").commitNow();
                assertNull(ReflectionHelpers.getField(dialog, "deleteSongs_SAFTreePicker"));
            }
        } finally {
            ReflectionHelpers.setStaticField(android.os.Build.VERSION.class, "SDK_INT", sdk);
        }
    }
}
