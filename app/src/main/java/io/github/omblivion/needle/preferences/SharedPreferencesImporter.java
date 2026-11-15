package io.github.omblivion.needle.preferences;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import io.github.omblivion.needle.R;
import io.github.omblivion.needle.util.OopsHandler;
import io.github.omblivion.needle.util.PreferenceUtil;
import io.github.omblivion.needle.util.SafeToast;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;

public class SharedPreferencesImporter extends AppCompatActivity {

    private Context context;
    private ActivityResultLauncher importFilePicker;

    private SharedPreferences sharedPreferences;

    static final String CURRENT_FILE_FORMAT = "json";

    static final int MIN_COMPATIBLE_VERSION = 192;
    static final int MAX_COMPATIBLE_VERSION = Integer.MAX_VALUE;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.context = this.getApplicationContext();
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this.context);
        importFilePicker = registerForActivityResult(new ActivityResultContracts.OpenDocument(), result -> {
            // Unless the selection has been cancelled, create the export file
            if(result != null) {
                importSettings(result);
            }
            // Finishes the last activity to return to the settings activity.
            this.finish();
        });
        importFilePicker.launch(new String[]{"text/plain"});
    }

    private void importSettings(Uri location) {
        try (ParcelFileDescriptor file = context.getContentResolver().openFileDescriptor(location, "r")) {
            if (file == null) throw new IOException("Could not open settings");
            JsonObject document;
            try (BufferedReader reader = new BufferedReader(new FileReader(file.getFileDescriptor()))) {
                document = new Gson().fromJson(reader, JsonObject.class);
            }
            if (document == null) throw new IllegalArgumentException("Empty settings");
            if (!CURRENT_FILE_FORMAT.equals(document.get(PreferenceUtil.FILE_FORMAT).getAsString())) {
                SafeToast.show(this.context, R.string.unsupported_saved_pref_format);
                return;
            }
            int version = document.get(PreferenceUtil.VERSION_CODE).getAsBigDecimal().intValueExact();
            if (version < MIN_COMPATIBLE_VERSION || version > MAX_COMPATIBLE_VERSION) {
                SafeToast.show(context, R.string.unsupported_saved_pref_version);
                return;
            }
            Map<String, Object> incoming = new HashMap<>();
            for (Map.Entry<String, JsonElement> entry : document.entrySet()) {
                incoming.put(entry.getKey(), entry.getValue());
            }
            // Validate the whole backup before obtaining an editor: no partial imports.
            Map<String, Object> validated = new HashMap<>();
            for (Map.Entry<String, Object> entry : PreferenceUtil.reducePreferencesToDeclared(
                    incoming, key -> key.isExportImportable).entrySet()) {
                String key = entry.getKey();
                if (key.equals(PreferenceUtil.FILE_FORMAT) || key.equals(PreferenceUtil.VERSION_CODE)
                        || key.equals(PreferenceUtil.VERSION_NAME)) continue;
                JsonPrimitive value = ((JsonElement) entry.getValue()).getAsJsonPrimitive();
                if (FLOAT_KEYS.contains(key)) {
                    if (!value.isNumber()) throw new IllegalArgumentException("Expected number: " + key);
                    float number = value.getAsFloat();
                    if (Float.isInfinite(number) || Float.isNaN(number)) throw new IllegalArgumentException("Invalid number: " + key);
                    validated.put(key, number);
                } else if (INT_KEYS.contains(key)) {
                    if (!value.isNumber()) throw new IllegalArgumentException("Expected integer: " + key);
                    int number = value.getAsBigDecimal().intValueExact();
                    // Eight is the largest grid exposed by the phone/tablet menus.
                    if (GRID_KEYS.contains(key) && (number < 1 || number > 8)) {
                        throw new IllegalArgumentException("Invalid grid size: " + key);
                    }
                    validated.put(key, number);
                } else if (STRING_KEYS.contains(key)) {
                    if (!value.isString()) throw new IllegalArgumentException("Expected string: " + key);
                    if (key.equals(PreferenceUtil.LIBRARY_CATEGORIES)) {
                        PreferenceUtil.parseLibraryCategoryInfos(value.getAsString());
                    }
                    validated.put(key, value.getAsString());
                } else {
                    if (!value.isBoolean()) throw new IllegalArgumentException("Expected boolean: " + key);
                    validated.put(key, value.getAsBoolean());
                }
            }
            SharedPreferences.Editor editor = sharedPreferences.edit();
            for (Map.Entry<String, Object> entry : validated.entrySet()) {
                Object value = entry.getValue();
                String key = entry.getKey();
                if (value instanceof Float) editor.putFloat(key, (Float) value);
                else if (value instanceof Integer) editor.putInt(key, (Integer) value);
                else if (value instanceof String) editor.putString(key, (String) value);
                else editor.putBoolean(key, (Boolean) value);
            }
            editor.apply();
        } catch (IOException | RuntimeException exception) {
            SafeToast.show(this.context, R.string.cannot_import_settings);
            OopsHandler.collectStackTrace(exception);
        }
    }

    private static final Set<String> FLOAT_KEYS = Set.of(
            PreferenceUtil.RG_PREAMP_WITH_TAG, PreferenceUtil.RG_PREAMP_WITHOUT_TAG);
    private static final Set<String> GRID_KEYS = Set.of(
            "album_grid_size", "album_grid_size_land", "song_grid_size", "song_grid_size_land",
            "artist_grid_size", "artist_grid_size_land");
    private static final Set<String> INT_KEYS = Set.of(
            PreferenceUtil.PRIMARY_COLOR, PreferenceUtil.ACCENT_COLOR,
            PreferenceUtil.NOW_PLAYING_SCREEN_ID, PreferenceUtil.ENQUEUE_SONGS_DEFAULT_CHOICE,
            "last_start_page", "last_music_chooser", "last_sleep_timer_value",
            "album_grid_size", "album_grid_size_land", "song_grid_size", "song_grid_size_land",
            "artist_grid_size", "artist_grid_size_land", "SHUFFLE_MODE", "REPEAT_MODE");
    private static final Set<String> STRING_KEYS = Set.of(
            PreferenceUtil.GENERAL_THEME, PreferenceUtil.AUTO_DOWNLOAD_IMAGES_POLICY,
            PreferenceUtil.LIBRARY_CATEGORIES, PreferenceUtil.THEME_STYLE, PreferenceUtil.RG_SOURCE_MODE_V2,
            "start_directory", "artist_sort_order", "album_sort_order", "song_sort_order", "file_sort_order",
            "last_added_sort_order", "not_recently_played_sort_order",
            "last_added_interval_v2", "recently_played_interval_v2", "not_recently_played_interval_v2");
}
