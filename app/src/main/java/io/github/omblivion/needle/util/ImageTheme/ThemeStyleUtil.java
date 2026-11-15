package io.github.omblivion.needle.util.ImageTheme;


import androidx.annotation.NonNull;

import io.github.omblivion.needle.util.PreferenceUtil;

public class ThemeStyleUtil {
    private static ThemeStyle sInstance;

    public static ThemeStyle updateInstance(@NonNull final String themeName) {
        synchronized (ThemeStyleUtil.class) {
            if (themeName.equals(PreferenceUtil.ROUNDED_THEME)) {
                sInstance = new MaterialTheme();
            } else {
                sInstance = new FlatTheme();
            }
            return sInstance;
        }
    }

    public static synchronized ThemeStyle getInstance() {
        if (sInstance == null) {
            return updateInstance(PreferenceUtil.getInstance().getThemeStyle());
        }
        return sInstance;
    }
}