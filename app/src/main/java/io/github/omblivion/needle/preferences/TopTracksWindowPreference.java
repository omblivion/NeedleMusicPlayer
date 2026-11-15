package io.github.omblivion.needle.preferences;

import android.content.Context;
import android.util.AttributeSet;

import com.kabouzeid.appthemehelper.common.prefs.supportv7.ATEDialogPreference;

/** Preference whose dialog exposes only the closed set of top-track windows. */
public class TopTracksWindowPreference extends ATEDialogPreference {
    public TopTracksWindowPreference(Context context) {
        super(context);
    }

    public TopTracksWindowPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public TopTracksWindowPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public TopTracksWindowPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }
}
