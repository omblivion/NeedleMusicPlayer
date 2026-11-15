package io.github.omblivion.needle.preferences;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;

import com.afollestad.materialdialogs.MaterialDialog;
import io.github.omblivion.needle.R;
import io.github.omblivion.needle.model.TopTracksWindow;
import io.github.omblivion.needle.util.PreferenceUtil;

/** Single-choice picker over the closed set of top-track windows. */
public class TopTracksWindowPreferenceDialog extends DialogFragment {
    @NonNull
    public static TopTracksWindowPreferenceDialog newInstance() {
        return new TopTracksWindowPreferenceDialog();
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        final Context context = requireContext();
        final TopTracksWindow[] windows = TopTracksWindow.values();
        final String[] labels = new String[windows.length];
        int selected = 0;
        final TopTracksWindow current = PreferenceUtil.getInstance().getTopTracksWindow();
        for (int i = 0; i < windows.length; i++) {
            labels[i] = context.getString(windows[i].labelRes);
            if (windows[i] == current) {
                selected = i;
            }
        }

        return new MaterialDialog.Builder(context)
                .title(R.string.pref_title_top_tracks_window)
                .items(labels)
                .itemsCallbackSingleChoice(selected, (materialDialog, view, which, text) -> {
                    PreferenceUtil.getInstance().setTopTracksWindow(windows[which]);
                    dismiss();
                    return true;
                })
                .negativeText(android.R.string.cancel)
                .build();
    }
}
