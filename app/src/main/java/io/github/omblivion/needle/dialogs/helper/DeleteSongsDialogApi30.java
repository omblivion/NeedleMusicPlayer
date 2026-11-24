package io.github.omblivion.needle.dialogs.helper;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.os.AsyncTask;
import android.os.Build.VERSION_CODES;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import io.github.omblivion.needle.R;
import io.github.omblivion.needle.helper.MusicPlayerRemote;
import io.github.omblivion.needle.model.Song;
import io.github.omblivion.needle.provider.UnplayableTrackStore;
import io.github.omblivion.needle.util.MusicUtil;
import io.github.omblivion.needle.util.OopsHandler;
import io.github.omblivion.needle.util.SafeToast;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.FutureTask;

/**
 * @author Karim Abou Zeid (kabouzeid), Aidan Follestad (afollestad)
 */
@RequiresApi(api = VERSION_CODES.R)
public class DeleteSongsDialogApi30 extends Fragment {
    private static final String SONGS = "songs";
    private static final String REQUEST_STARTED = "request_started";
    private static final String FINISHED = "finished";

    private boolean requestStarted;
    private boolean finished;
    private FutureTask<Void> preparation;
    private FutureTask<Void> reportCleanup;
    private PendingIntent preparedRequest;
    private boolean preparationComplete;
    ArrayList<Song> songsToRemove;

    private ActivityResultLauncher<IntentSenderRequest> deleteRequestApi30;

    @NonNull
    public static DeleteSongsDialogApi30 create(Song song) {
        ArrayList<Song> list = new ArrayList<>();
        list.add(song);
        return create(list);
    }

    @NonNull
    public static DeleteSongsDialogApi30 create(Collection<? extends Song> songs) {
        DeleteSongsDialogApi30 dialog = new DeleteSongsDialogApi30();
        Bundle args = new Bundle();
        args.putParcelableArrayList(SONGS, new ArrayList<>(songs));
        dialog.setArguments(args);
        return dialog;
    }

    public void show(@NonNull FragmentManager manager, @Nullable String tag) {
        FragmentTransaction ft = manager.beginTransaction();
        ft.add(this, tag);
        ft.commit();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        deleteRequestApi30 = registerForActivityResult(
                new ActivityResultContracts.StartIntentSenderForResult(),
                result -> handleDeleteResult(result.getResultCode()));

        songsToRemove = requireArguments().getParcelableArrayList(SONGS);

        if (savedInstanceState != null) {
            requestStarted = savedInstanceState.getBoolean(REQUEST_STARTED);
            finished = savedInstanceState.getBoolean(FINISHED);
        }
        if (finished) {
            retire();
        } else if (!requestStarted) {
            // Android 11+ uses the MediaStore confirmation, not legacy SAF permissions.
            deleteSongs(songsToRemove);
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        if (preparationComplete) onDeleteRequestPrepared(preparedRequest);
    }

    @Override
    public void onDestroy() {
        if (preparation != null) preparation.cancel(true);
        super.onDestroy();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(REQUEST_STARTED, requestStarted);
        outState.putBoolean(FINISHED, finished);
    }

    @MainThread
    void handleDeleteResult(int resultCode) {
        if (finished) return;
        finished = true;
        if (resultCode == Activity.RESULT_OK) {
            Context context = requireContext().getApplicationContext();
            List<Song> songs = new ArrayList<>(songsToRemove);
            reportCleanup = new FutureTask<>(() -> {
                try {
                    DeleteSongsHelper.managePlayingSong(new ArrayList<>(songs));
                    MusicPlayerRemote.removeFromQueue(songs);
                    io.github.omblivion.needle.discog.Discography.getInstance().confirmDeletion(songs);
                    UnplayableTrackStore store = UnplayableTrackStore.getInstance(context);
                    for (Song song : songs) store.remove(song);
                } catch (RuntimeException error) {
                    OopsHandler.collectStackTrace(error);
                }
                return null;
            });
            // Confirmed deletion cleanup must survive removal of this helper.
            AsyncTask.THREAD_POOL_EXECUTOR.execute(reportCleanup);
            SafeToast.show(getActivity(), getString(R.string.deleted_x_songs, songsToRemove.size()));
        } else {
            SafeToast.show(getActivity(), getString(R.string.saf_delete_failed,
                    Integer.toString(songsToRemove.size())));
        }
        retire();
    }

    private void retire() {
        getParentFragmentManager().beginTransaction().remove(this).commitAllowingStateLoss();
    }

    void deleteSongs(List<Song> songsToRemove) {
        Context context = requireContext().getApplicationContext();
        List<Song> songs = new ArrayList<>(songsToRemove);
        WeakReference<DeleteSongsDialogApi30> target = new WeakReference<>(this);
        Handler mainHandler = new Handler(Looper.getMainLooper());
        preparation = new FutureTask<>(() -> {
            PendingIntent request = MusicUtil.prepareDeleteRequest(context, songs);
            mainHandler.post(() -> {
                DeleteSongsDialogApi30 fragment = target.get();
                if (fragment != null && fragment.isAdded() && fragment.preparation != null
                        && !fragment.preparation.isCancelled()) {
                    fragment.onDeleteRequestPrepared(request);
                }
            });
            return null;
        });
        AsyncTask.THREAD_POOL_EXECUTOR.execute(preparation);
    }

    @MainThread
    void onDeleteRequestPrepared(@Nullable PendingIntent request) {
        if (finished || requestStarted) return;
        if (getParentFragmentManager().isStateSaved()) {
            preparedRequest = request;
            preparationComplete = true;
            return;
        }
        preparedRequest = null;
        preparationComplete = false;
        if (request == null) {
            handleDeleteResult(Activity.RESULT_CANCELED);
            return;
        }
        requestStarted = true;
        try {
            launchConfirmation(request);
        } catch (RuntimeException error) {
            OopsHandler.collectStackTrace(error);
            handleDeleteResult(Activity.RESULT_CANCELED);
        }
    }

    @MainThread
    void launchConfirmation(@NonNull PendingIntent request) {
        deleteRequestApi30.launch(new IntentSenderRequest.Builder(request).build());
    }
}
