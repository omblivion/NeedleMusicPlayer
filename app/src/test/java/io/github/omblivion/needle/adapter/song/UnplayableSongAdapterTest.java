package io.github.omblivion.needle.adapter.song;

import static org.junit.Assert.*;

import android.view.ActionMode;
import android.widget.FrameLayout;
import androidx.appcompat.view.menu.MenuBuilder;

import io.github.omblivion.needle.App;
import io.github.omblivion.needle.R;
import io.github.omblivion.needle.model.Song;
import io.github.omblivion.needle.ui.activities.base.AbsThemeActivity;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = App.class)
public class UnplayableSongAdapterTest {
    public static class TestActivity extends AbsThemeActivity {}

    @Test
    public void filePathAndDeviceDeletionAreAvailableForSingleAndMultipleSongs() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            TestActivity activity = controller.get();
            Song song = new Song(42, "Broken", 0, 0, 20000, "/music/broken.mp3",
                    0, 0, 1, "Album", List.of("Artist"));
            UnplayableSongAdapter adapter = new UnplayableSongAdapter(activity, List.of(song),
                    () -> 0xff123456);
            FrameLayout parent = new FrameLayout(activity);
            SongAdapter.ViewHolder holder = adapter.onCreateViewHolder(parent, adapter.getItemViewType(0));
            MenuBuilder menu = new MenuBuilder(activity);
            activity.getMenuInflater().inflate(holder.getSongMenuRes(), menu);
            assertNotNull(menu.findItem(R.id.action_delete_from_device));
            assertEquals("/music/broken.mp3", adapter.getSongText(song));
            assertEquals(1, adapter.getItemCount());
            ReflectionHelpers.callInstanceMethod(adapter, "toggleChecked",
                    ReflectionHelpers.ClassParameter.from(int.class, 0));
            ActionMode mode = ReflectionHelpers.getField(adapter, "actionMode");
            assertNotNull(mode);
            assertNotNull(mode.getMenu().findItem(R.id.action_delete_from_device));
        }
    }
}
