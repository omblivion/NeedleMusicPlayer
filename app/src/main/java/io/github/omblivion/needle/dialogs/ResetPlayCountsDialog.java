package io.github.omblivion.needle.dialogs;

import android.app.Dialog;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;

import com.afollestad.materialdialogs.MaterialDialog;
import io.github.omblivion.needle.R;
import io.github.omblivion.needle.helper.menu.ResetPlayCountsHelper;
import io.github.omblivion.needle.model.Song;

import java.util.concurrent.Executors;

/** Confirms clearing one song's play statistics from the most-played list. */
public class ResetPlayCountsDialog extends DialogFragment {

    @NonNull
    public static ResetPlayCountsDialog create(@NonNull Song song) {
        ResetPlayCountsDialog dialog = new ResetPlayCountsDialog();
        Bundle args = new Bundle();
        args.putParcelable("song", song);
        dialog.setArguments(args);
        return dialog;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        final Song song = getArguments().getParcelable("song");

        return new MaterialDialog.Builder(requireActivity())
                .title(R.string.reset_play_counts_title)
                .content(R.string.reset_play_counts_message)
                .positiveText(R.string.reset_action)
                .negativeText(android.R.string.cancel)
                .onPositive((dialog, which) -> {
                    if (getActivity() == null || song == null) {
                        return;
                    }
                    ResetPlayCountsHelper.reset(getActivity(), song, Executors.newSingleThreadExecutor());
                })
                .build();
    }
}
