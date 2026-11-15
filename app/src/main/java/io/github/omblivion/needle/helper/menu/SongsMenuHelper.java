package io.github.omblivion.needle.helper.menu;

import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentActivity;

import io.github.omblivion.needle.R;
import io.github.omblivion.needle.dialogs.AddToPlaylistDialog;
import io.github.omblivion.needle.dialogs.helper.DeleteSongsHelper;
import io.github.omblivion.needle.helper.MusicPlayerRemote;
import io.github.omblivion.needle.model.Song;

import java.util.Collection;

/**
 * @author Karim Abou Zeid (kabouzeid)
 */
public class SongsMenuHelper {
    public static void handleMenuClick(@NonNull final FragmentActivity activity, @NonNull final Collection<? extends Song> songs, final int menuItemId) {
        if (menuItemId == R.id.action_play_next) {
            MusicPlayerRemote.playNext(songs);
        } else if (menuItemId == R.id.action_add_to_current_playing) {
            MusicPlayerRemote.enqueue(songs);
        } else if (menuItemId == R.id.action_add_to_playlist) {
            AddToPlaylistDialog.create(songs).show(activity.getSupportFragmentManager(), "ADD_PLAYLIST");
        } else if (menuItemId == R.id.action_delete_from_device) {
            DeleteSongsHelper.delete(songs, activity.getSupportFragmentManager(), "DELETE_SONGS");
        }
    }
}
