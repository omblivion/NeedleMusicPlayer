package io.github.omblivion.needle.ui.activities;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.appcompat.widget.SearchView;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.loader.app.LoaderManager;
import androidx.loader.content.Loader;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.kabouzeid.appthemehelper.ThemeStore;
import io.github.omblivion.needle.R;
import io.github.omblivion.needle.adapter.SearchAdapter;
import io.github.omblivion.needle.databinding.ActivitySearchBinding;
import io.github.omblivion.needle.discog.Discography;
import io.github.omblivion.needle.interfaces.LoaderIds;
import io.github.omblivion.needle.loader.AlbumLoader;
import io.github.omblivion.needle.loader.ArtistLoader;
import io.github.omblivion.needle.loader.SongLoader;
import io.github.omblivion.needle.misc.WrappedAsyncTaskLoader;
import io.github.omblivion.needle.model.Album;
import io.github.omblivion.needle.model.Artist;
import io.github.omblivion.needle.model.Song;
import io.github.omblivion.needle.ui.activities.base.AbsMusicServiceActivity;
import io.github.omblivion.needle.util.ImageTheme.ThemeStyleUtil;
import io.github.omblivion.needle.util.StringUtil;
import io.github.omblivion.needle.util.Util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SearchActivity extends AbsMusicServiceActivity implements SearchView.OnQueryTextListener, LoaderManager.LoaderCallbacks<List<Object>> {

    public static final String QUERY = "query";
    private static final int LOADER_ID = LoaderIds.SEARCH_ACTIVITY;
    private static final long QUERY_DEBOUNCE_MS = 120;

    RecyclerView recyclerView;
    Toolbar toolbar;
    TextView empty;

    SearchView searchView;

    SearchAdapter adapter;
    private String query;
    private String requestedQuery;
    private long requestedRevision;
    private final Handler queryHandler = new Handler(Looper.getMainLooper());
    private final Runnable runQuery = this::reloadSearch;
    private final Runnable onCatalogChanged = this::reloadSearch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ActivitySearchBinding binding = ActivitySearchBinding.inflate(LayoutInflater.from(this));
        recyclerView = binding.recyclerView;
        toolbar = binding.toolbar;
        empty = binding.empty;
        setContentView(binding.getRoot());

        setDrawUnderStatusbar();

        setStatusbarColorAuto();
        setNavigationbarColorAuto();
        setTaskDescriptionColorAuto();

        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        ThemeStyleUtil.getInstance().setHeaderPadding(recyclerView, getResources().getDisplayMetrics().density);

        //change scrollbar color to follow secondary color
        Drawable unwrappedDrawable = AppCompatResources.getDrawable(this, R.drawable.scrollbar_vertical_thumb);
        Drawable wrappedDrawable = DrawableCompat.wrap(unwrappedDrawable);
        DrawableCompat.setTint(wrappedDrawable, ThemeStore.accentColor(this));

        adapter = new SearchAdapter(this, Collections.emptyList());
        adapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
            @Override
            public void onChanged() {
                super.onChanged();
                empty.setVisibility(adapter.getItemCount() < 1 ? View.VISIBLE : View.GONE);
            }
        });
        recyclerView.setAdapter(adapter);

        recyclerView.setOnTouchListener((v, event) -> {
            hideSoftKeyboard();
            return false;
        });

        setUpToolBar();

        if (savedInstanceState != null) {
            query = savedInstanceState.getString(QUERY);
        }

        // initLoader may retain a loader for a different query after rotation.
        rememberRequest(LoaderManager.getInstance(this).initLoader(LOADER_ID, null, this));
    }

    @Override
    protected void onStart() {
        super.onStart();
        Discography discography = Discography.getInstance();
        discography.addChangedListener(onCatalogChanged);
        if (!TextUtils.equals(query, requestedQuery) || requestedRevision != discography.getRevision()) {
            reloadSearch();
        }
    }

    @Override
    protected void onStop() {
        queryHandler.removeCallbacks(runQuery);
        Discography.getInstance().removeChangedListener(onCatalogChanged);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        queryHandler.removeCallbacks(runQuery);
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(QUERY, query);
    }

    private void setUpToolBar() {
        toolbar.setBackgroundColor(ThemeStore.primaryColor(this));
        setSupportActionBar(toolbar);
        //noinspection ConstantConditions
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_search, menu);

        final MenuItem searchItem = menu.findItem(R.id.search);
        searchView = (SearchView) searchItem.getActionView();
        searchView.setQueryHint(getString(R.string.search_hint));
        searchView.setMaxWidth(Integer.MAX_VALUE);

        searchItem.expandActionView();
        searchItem.setOnActionExpandListener(new MenuItem.OnActionExpandListener() {
            @Override
            public boolean onMenuItemActionExpand(MenuItem item) {
                return true;
            }

            @Override
            public boolean onMenuItemActionCollapse(MenuItem item) {
                onBackPressed();
                return false;
            }
        });

        searchView.setQuery(query, false);
        searchView.setOnQueryTextListener(this);

        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            onBackPressed();
        }
        return super.onOptionsItemSelected(item);
    }

    private void reloadSearch() {
        queryHandler.removeCallbacks(runQuery);
        rememberRequest(LoaderManager.getInstance(this).restartLoader(LOADER_ID, null, this));
    }

    private void rememberRequest(Loader<List<Object>> loader) {
        AsyncSearchResultLoader search = (AsyncSearchResultLoader) loader;
        requestedQuery = search.query;
        requestedRevision = search.catalogRevision;
    }

    @Override
    public void onPlayingMetaChanged() {
        super.onPlayingMetaChanged();

        // give the adapter a chance to update the decoration
        adapter.notifyDataSetChanged();
    }

    @Override
    public void onPlayStateChanged() {
        super.onPlayStateChanged();

        // give the adapter a chance to update the decoration
        adapter.notifyDataSetChanged();
    }

    @Override
    public boolean onQueryTextSubmit(String query) {
        if (!TextUtils.equals(requestedQuery, query)) {
            this.query = query;
            reloadSearch();
        }
        hideSoftKeyboard();
        return true;
    }

    @Override
    public boolean onQueryTextChange(String newText) {
        if (!TextUtils.equals(query, newText)) {
            query = newText;
            queryHandler.removeCallbacks(runQuery);
            if (!TextUtils.isEmpty(newText)) empty.setVisibility(View.GONE);
            queryHandler.postDelayed(runQuery, QUERY_DEBOUNCE_MS);
        }
        return false;
    }

    private void hideSoftKeyboard() {
        Util.hideSoftKeyboard(SearchActivity.this);
        if (searchView != null) {
            searchView.clearFocus();
        }
    }

    @Override
    @NonNull
    public Loader<List<Object>> onCreateLoader(int id, Bundle args) {
        return new AsyncSearchResultLoader(this, query, Discography.getInstance().getRevision());
    }

    @Override
    public void onLoadFinished(@NonNull Loader<List<Object>> loader, List<Object> data) {
        if (loader instanceof AsyncSearchResultLoader search
                && TextUtils.equals(search.query, query)
                && search.catalogRevision == Discography.getInstance().getRevision()) {
            adapter.swapDataSet(data);
        }
    }

    @Override
    public void onLoaderReset(@NonNull Loader<List<Object>> loader) {
        adapter.swapDataSet(Collections.emptyList());
    }

    private static class AsyncSearchResultLoader extends WrappedAsyncTaskLoader<List<Object>> {
        private final String query;
        private final long catalogRevision;

        public AsyncSearchResultLoader(Context context, String query, long catalogRevision) {
            super(context);
            this.query = query;
            this.catalogRevision = catalogRevision;
        }

        @Override
        public List<Object> loadInBackground() {
            List<Object> results = new ArrayList<>();
            if (!TextUtils.isEmpty(query) && catalogRevision == Discography.getInstance().getRevision()) {
                final String normalizedQuery = StringUtil.unicodeNormalize(query.trim());

                List<Song> songs = SongLoader.getSongs(normalizedQuery);
                if (!songs.isEmpty()) {
                    results.add(getContext().getResources().getString(R.string.songs));
                    results.addAll(songs);
                }

                List<Artist> artists = ArtistLoader.getArtists(normalizedQuery);
                if (!artists.isEmpty()) {
                    results.add(getContext().getResources().getString(R.string.artists));
                    results.addAll(artists);
                }

                List<Album> albums = AlbumLoader.getAlbums(normalizedQuery);
                if (!albums.isEmpty()) {
                    results.add(getContext().getResources().getString(R.string.albums));
                    results.addAll(albums);
                }
            }
            return results;
        }
    }
}
