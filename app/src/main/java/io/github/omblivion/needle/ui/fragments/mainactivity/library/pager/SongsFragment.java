package io.github.omblivion.needle.ui.fragments.mainactivity.library.pager;

import android.content.Context;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.loader.app.LoaderManager;
import androidx.loader.content.Loader;
import androidx.recyclerview.widget.GridLayoutManager;

import io.github.omblivion.needle.R;
import io.github.omblivion.needle.adapter.song.ShuffleButtonSongAdapter;
import io.github.omblivion.needle.adapter.song.SongAdapter;
import io.github.omblivion.needle.interfaces.LoaderIds;
import io.github.omblivion.needle.loader.SongLoader;
import io.github.omblivion.needle.misc.WrappedAsyncTaskLoader;
import io.github.omblivion.needle.model.Song;
import io.github.omblivion.needle.util.PreferenceUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * @author Karim Abou Zeid (kabouzeid)
 */
public class SongsFragment extends AbsLibraryPagerRecyclerViewCustomGridSizeFragment<SongAdapter, GridLayoutManager> implements LoaderManager.LoaderCallbacks<ArrayList<Song>> {

    private static final int LOADER_ID = LoaderIds.SONGS_FRAGMENT;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getLoaderManager().initLoader(LOADER_ID, null, this);
    }

    @NonNull
    @Override
    protected GridLayoutManager createLayoutManager() {
        return new GridLayoutManager(getActivity(), getGridSize());
    }

    @NonNull
    @Override
    protected SongAdapter createAdapter() {
        int itemLayoutRes = getItemLayoutRes();
        notifyLayoutResChanged(itemLayoutRes);
        boolean usePalette = loadUsePalette();
        List<? extends Song> dataSet = getAdapter() == null ? new ArrayList<>() : getAdapter().getDataSet();

        if (getGridSize() <= getMaxGridSizeForList()) {
            return new ShuffleButtonSongAdapter(
                    getLibraryFragment().getMainActivity(),
                    dataSet,
                    itemLayoutRes,
                    usePalette,
                    getLibraryFragment().getMainActivity());
        }
        return new SongAdapter(
                getLibraryFragment().getMainActivity(),
                dataSet,
                itemLayoutRes,
                usePalette,
                getLibraryFragment().getMainActivity());
    }

    @Override
    protected int getEmptyMessage() {
        return R.string.no_songs;
    }

    @Override
    public void onPlayingMetaChanged() {
        super.onPlayingMetaChanged();

        // give the adapter a chance to update the decoration
        getAdapter().notifyDataSetChanged();
    }

    @Override
    public void onPlayStateChanged() {
        super.onPlayStateChanged();

        // give the adapter a chance to update the decoration
        getAdapter().notifyDataSetChanged();
    }

    @Override
    protected String loadSortOrder() {
        return PreferenceUtil.getInstance().getSongSortOrder();
    }

    @Override
    protected void saveSortOrder(String sortOrder) {
        PreferenceUtil.getInstance().setSongSortOrder(sortOrder);
    }

    @Override
    protected int loadGridSize() {
        return PreferenceUtil.getInstance().getSongGridSize(getActivity());
    }

    @Override
    protected void saveGridSize(int gridSize) {
        PreferenceUtil.getInstance().setSongGridSize(gridSize);
    }

    @Override
    protected int loadGridSizeLand() {
        return PreferenceUtil.getInstance().getSongGridSizeLand(getActivity());
    }

    @Override
    protected void saveGridSizeLand(int gridSize) {
        PreferenceUtil.getInstance().setSongGridSizeLand(gridSize);
    }

    @Override
    public void saveShowFooter(boolean showFooter) {
        // TODO
    }

    @Override
    public boolean loadShowFooter() {
        return true; // TODO
    }

    @Override
    public void setShowFooter(boolean showFooter) {
        // TODO
    }

    @Override
    public void saveUsePalette(boolean usePalette) {
        PreferenceUtil.getInstance().setSongColoredFooters(usePalette);
    }

    @Override
    public boolean loadUsePalette() {
        return PreferenceUtil.getInstance().songColoredFooters();
    }

    @Override
    public void setUsePalette(boolean usePalette) {
        getAdapter().usePalette(usePalette);
    }

    @Override
    protected void setGridSize(int gridSize) {
        getLayoutManager().setSpanCount(gridSize);
        getAdapter().notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Loader<ArrayList<Song>> onCreateLoader(int id, Bundle args) {
        return new AsyncSongLoader(getActivity());
    }

    @Override
    public void onLoadFinished(@NonNull Loader<ArrayList<Song>> loader, ArrayList<Song> data) {
        getAdapter().swapDataSet(data);
    }

    @Override
    public void onLoaderReset(@NonNull Loader<ArrayList<Song>> loader) {
        getAdapter().swapDataSet(new ArrayList<>());
    }

    private static class AsyncSongLoader extends WrappedAsyncTaskLoader<ArrayList<Song>> {
        public AsyncSongLoader(Context context) {
            super(context);
        }

        @Override
        public ArrayList<Song> loadInBackground() {
            return SongLoader.getAllSongs();
        }
    }

    @Override
    public void reload() {
        try {
            LoaderManager.getInstance(this).restartLoader(LOADER_ID, null, this);
        } catch (IllegalStateException ignored) {}
    }
}
