package io.github.omblivion.needle.ui.fragments.mainactivity;

import android.os.Bundle;

import androidx.fragment.app.Fragment;

import io.github.omblivion.needle.ui.activities.MainActivity;

/**
 * @author Karim Abou Zeid (kabouzeid)
 */
public abstract class AbsMainActivityFragment extends Fragment {

    public MainActivity getMainActivity() {
        return (MainActivity) getActivity();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
    }
}
