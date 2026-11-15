package io.github.omblivion.needle.ui.fragments.mainactivity.library.pager;

import android.os.Bundle;

import io.github.omblivion.needle.ui.fragments.AbsMusicServiceFragment;
import io.github.omblivion.needle.ui.fragments.mainactivity.library.LibraryFragment;

/**
 * @author Karim Abou Zeid (kabouzeid)
 */
public class AbsLibraryPagerFragment extends AbsMusicServiceFragment {
    public LibraryFragment getLibraryFragment() {
        return (LibraryFragment) getParentFragment();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
    }
}
