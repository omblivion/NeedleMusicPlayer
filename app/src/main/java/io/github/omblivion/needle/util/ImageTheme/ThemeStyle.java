package io.github.omblivion.needle.util.ImageTheme;

import android.app.Activity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.recyclerview.widget.RecyclerView;
import io.github.omblivion.needle.adapter.SearchAdapter;
import io.github.omblivion.needle.adapter.SearchAdapter.ViewHolder;

public interface ThemeStyle {
    boolean showSongAlbumArt();

    float getAlbumRadiusImage(Activity activity);
    float getArtistRadiusImage(Activity activity);
    void setHeightListItem(View itemView, float density);
    void setHeaderPadding(RecyclerView recyclerView, float density);
    void setHeaderText(ViewHolder holder, AppCompatActivity activity, String title);
    void setSearchCardItemStyle(View itemView, AppCompatActivity activity);
    void setPlaylistCardItemStyle(View itemView, AppCompatActivity activity);

    void setDragView(AppCompatImageView dragView);

    SearchAdapter.ViewHolder HeaderViewHolder(SearchAdapter adapter, LayoutInflater inflater, @NonNull ViewGroup parent, boolean attachToParent);
    int getShortSeparatorVisibilityState();

    int getBottomSheetStyle();
}