package io.github.omblivion.needle.ui.activities.tageditor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;

import androidx.activity.result.ActivityResultLauncher;

import io.github.omblivion.needle.App;
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
@Config(sdk = 21, application = App.class)
public class TagEditorPickerTest {
    public static class Editor extends SongTagEditorActivity {
        @Override protected void setUpViews() {
            // Do not read actual audio files; keep the real activity-result wiring.
        }
    }

    @Before
    public void resetPreferences() {
        ReflectionHelpers.setStaticField(PreferenceUtil.class, "sInstance", null);
    }

    @Test
    public void cancellingTreePickerDoesNotPersistPermissionOrStartWriting() {
        try (org.robolectric.android.controller.ActivityController<Editor> controller =
                     Robolectric.buildActivity(Editor.class).setup()) {
            Editor editor = controller.get();
            editor.fab.setEnabled(false);
            ActivityResultLauncher<Uri> launcher = ReflectionHelpers.getField(editor, "writeTagsApi21_SAFTreePicker");
            launcher.launch(null);
            ShadowActivity.IntentForResult request = shadowOf(editor).getNextStartedActivityForResult();
            assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, request.intent.getAction());
            editor.getActivityResultRegistry().dispatchResult(request.requestCode, Activity.RESULT_CANCELED, null);
            assertEquals("", PreferenceUtil.getInstance().getSAFSDCardUri());
            assertEquals(0, editor.getContentResolver().getPersistedUriPermissions().size());
            assertTrue(editor.fab.isEnabled());
        }
    }

    @Test
    public void cancellingLegacyFilePickerDoesNotStartWriting() {
        try (org.robolectric.android.controller.ActivityController<Editor> controller =
                     Robolectric.buildActivity(Editor.class).setup()) {
            Editor editor = controller.get();
            editor.fab.setEnabled(false);
            ActivityResultLauncher<String> launcher = ReflectionHelpers.getField(editor, "writeTagsApi19_SAFFilePicker");
            launcher.launch("track.mp3");
            ShadowActivity.IntentForResult request = shadowOf(editor).getNextStartedActivityForResult();
            editor.getActivityResultRegistry().dispatchResult(request.requestCode, Activity.RESULT_CANCELED, null);
            assertEquals("", PreferenceUtil.getInstance().getSAFSDCardUri());
            assertTrue(editor.fab.isEnabled());
        }
    }

    @Test
    public void legacySdkBranchDoesNotRegisterTreePicker() {
        // Robolectric 4.14 has no API 19 image. Exercise branching on the API 21
        // runtime without claiming this is an API 19 compatibility/device test.
        int sdk = android.os.Build.VERSION.SDK_INT;
        ReflectionHelpers.setStaticField(android.os.Build.VERSION.class, "SDK_INT", 19);
        try {
            try (org.robolectric.android.controller.ActivityController<Editor> controller =
                         Robolectric.buildActivity(Editor.class).setup()) {
                assertNull(ReflectionHelpers.getField(controller.get(), "writeTagsApi21_SAFTreePicker"));
            }
        } finally {
            ReflectionHelpers.setStaticField(android.os.Build.VERSION.class, "SDK_INT", sdk);
        }
    }

    @Test
    public void cancellingGuideRestoresSaveAction() {
        try (org.robolectric.android.controller.ActivityController<Editor> controller =
                     Robolectric.buildActivity(Editor.class).setup()) {
            Editor editor = controller.get();
            editor.fab.setEnabled(false);
            ActivityResultLauncher<Intent> launcher = ReflectionHelpers.getField(editor, "writeTagsApi21_SAFGuide");
            launcher.launch(new Intent(editor, io.github.omblivion.needle.ui.activities.saf.SAFGuideActivity.class));
            ShadowActivity.IntentForResult request = shadowOf(editor).getNextStartedActivityForResult();
            editor.getActivityResultRegistry().dispatchResult(request.requestCode, Activity.RESULT_CANCELED, null);
            assertTrue(editor.fab.isEnabled());
        }
    }
}
