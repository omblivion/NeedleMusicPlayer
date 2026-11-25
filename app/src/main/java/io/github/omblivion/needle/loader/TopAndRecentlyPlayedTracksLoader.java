/*
* Copyright (C) 2014 The CyanogenMod Project
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package io.github.omblivion.needle.loader;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteException;

import androidx.annotation.NonNull;

import io.github.omblivion.needle.discog.Discography;
import io.github.omblivion.needle.model.Song;
import io.github.omblivion.needle.provider.HistoryStore;
import io.github.omblivion.needle.provider.ReferenceKey;
import io.github.omblivion.needle.provider.SongPlayCountStore;
import io.github.omblivion.needle.sort.SongSortOrder;
import io.github.omblivion.needle.util.OopsHandler;
import io.github.omblivion.needle.util.PreferenceUtil;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Map;

public class TopAndRecentlyPlayedTracksLoader {
    @NonNull
    public static ArrayList<Song> getRecentlyPlayedTracks(@NonNull final Context context) {
        final long cutoff = PreferenceUtil.getInstance().getRecentlyPlayedCutoffTimeMillis();
        if (cutoff == 0) {return new ArrayList<>();}

        return resolveHistory(HistoryStore.getInstance(context), cutoff);
    }

    @NonNull
    public static ArrayList<Song> getNotRecentlyPlayedTracks(@NonNull final Context context) {
        final long cutoff = PreferenceUtil.getInstance().getNotRecentlyPlayedCutoffTimeMillis();
        if (cutoff == 0) {return new ArrayList<>();}

        HistoryStore historyStore = HistoryStore.getInstance(context);
        ArrayList<Song> result = new ArrayList<>();

        // Collect not played songs
        Discography discography = Discography.getInstance();
        HashSet<Long> playedSongIds = new HashSet<>();
        for (Song song : resolveHistory(historyStore, 0)) playedSongIds.add(song.id);
        @NonNull final String sortOrderStr = PreferenceUtil.getInstance().getNotRecentlyPlayedSortOrder();
        Comparator<Song> sortOrder = sortOrderStr.equals(PreferenceUtil.ALBUM_SORT_ORDER) ? SongSortOrder.BY_ALBUM_DATE_ADDED : SongSortOrder.BY_DATE_ADDED;
        ArrayList<Song> allSongs = discography.getAllSongs(sortOrder);

        for (Song song : allSongs) {
            if (!playedSongIds.contains(song.id)) {
                result.add(song);
            }
        }

        // Collect not recently played songs
        result.addAll(resolveHistory(historyStore, -cutoff));
        return result;
    }

    private static ArrayList<Song> resolveHistory(HistoryStore store, long cutoff) {
        Map<String, Song> byPath = Discography.getInstance().getSongsByCanonicalPath();
        HashSet<String> seen = new HashSet<>();
        ArrayList<Song> result = new ArrayList<>();
        for (ReferenceKey key : store.getRecentKeys(cutoff)) {
            if (key.path().isEmpty() || !seen.add(key.path())) continue;
            Song song = byPath.get(key.path());
            if (song != null) result.add(song);
        }
        return result;
    }

    @NonNull
    public static ArrayList<Song> getTopTracks(@NonNull final Context context) {
        final boolean enabled = PreferenceUtil.getInstance().maintainTopTrackPlaylist();
        if (!enabled) {return new ArrayList<>();}

        final int NUMBER_OF_TOP_TRACKS = 100;
        try (Cursor cursor = SongPlayCountStore.getInstance(context).getTopPlayedResults(
                0, PreferenceUtil.getInstance().getTopTracksWindow(),
                System.currentTimeMillis())) {
            Map<String, Song> byPath = Discography.getInstance().getSongsByCanonicalPath();
            ArrayList<Song> result = new ArrayList<>();
            int pathColumn = cursor.getColumnIndexOrThrow(SongPlayCountStore.Columns.PATH);
            while (result.size() < NUMBER_OF_TOP_TRACKS && cursor.moveToNext()) {
                String path = cursor.getString(pathColumn);
                if (path.isEmpty()) continue;
                Song song = byPath.get(path);
                if (song != null) result.add(song);
            }
            return result;
        } catch (SQLiteException|IllegalStateException exception) {
            OopsHandler.collectStackTrace(exception);
            return new ArrayList<>();
        }
    }

}
