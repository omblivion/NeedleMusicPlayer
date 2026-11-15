package io.github.omblivion.needle.adapter;

import static io.github.omblivion.needle.util.ViewUtil.NEEDLE_ALBUM_ART_SCALE_TYPE;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import io.github.omblivion.needle.databinding.FragmentAlbumCoverBinding;
import io.github.omblivion.needle.glide.GlideApp;
import io.github.omblivion.needle.glide.NeedleColoredTarget;
import io.github.omblivion.needle.glide.NeedleGlideExtension;
import io.github.omblivion.needle.misc.CustomFragmentStatePagerAdapter;
import io.github.omblivion.needle.model.Song;

import java.util.ArrayList;
import java.util.List;

/**
 * @author Karim Abou Zeid (kabouzeid)
 */
public class AlbumCoverPagerAdapter extends CustomFragmentStatePagerAdapter {

    private final ArrayList<Song> dataSet;

    private AlbumCoverFragment.ColorReceiver currentColorReceiver;
    private int currentColorReceiverPosition = -1;

    public AlbumCoverPagerAdapter(FragmentManager fm, List<? extends Song> dataSet) {
        super(fm);
        // Make a copy to avoid race condition
        // i.e. the playing queue is modified, the UI code detects the change and crash
        // before this class gets a chance to be notified
        this.dataSet = new ArrayList<>(dataSet);
    }

    @Override
    public Fragment getItem(final int position) {
        return AlbumCoverFragment.newInstance(dataSet.get(position));
    }

    @Override
    public int getCount() {
        return dataSet.size();
    }

    @Override
    @NonNull
    public Object instantiateItem(@NonNull ViewGroup container, int position) {
        Object o = super.instantiateItem(container, position);
        if (currentColorReceiver != null && currentColorReceiverPosition == position) {
            receiveColor(currentColorReceiver, currentColorReceiverPosition);
        }
        return o;
    }

    /**
     * Only the latest passed {@link AlbumCoverFragment.ColorReceiver} is guaranteed to receive a response
     */
    public void receiveColor(AlbumCoverFragment.ColorReceiver colorReceiver, int position) {
        AlbumCoverFragment fragment = (AlbumCoverFragment) getFragment(position);
        if (fragment != null) {
            currentColorReceiver = null;
            currentColorReceiverPosition = -1;
            fragment.receiveColor(colorReceiver, position);
        } else {
            currentColorReceiver = colorReceiver;
            currentColorReceiverPosition = position;
        }
    }

    public static class AlbumCoverFragment extends Fragment {
        private static final String SONG_ARG = "song";

        ImageView albumCover;

        private boolean isColorReady;
        private int color;
        private Song song;
        private ColorReceiver colorReceiver;
        private int request;

        public static AlbumCoverFragment newInstance(final Song song) {
            AlbumCoverFragment frag = new AlbumCoverFragment();
            final Bundle args = new Bundle();
            args.putParcelable(SONG_ARG, song);
            frag.setArguments(args);
            return frag;
        }

        @Override
        public void onCreate(final Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            song = getArguments().getParcelable(SONG_ARG);
        }

        @Override
        public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
            FragmentAlbumCoverBinding binding = FragmentAlbumCoverBinding.inflate(inflater, container, false);
            albumCover = binding.playerImage;
            return binding.getRoot();
        }

        @Override
        public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
            super.onViewCreated(view, savedInstanceState);
            albumCover.setScaleType(NEEDLE_ALBUM_ART_SCALE_TYPE);
            loadAlbumCover();
        }

        @Override
        public void onDestroyView() {
            super.onDestroyView();
            colorReceiver = null;
        }

        private void loadAlbumCover() {
            GlideApp.with(this)
                    .asBitmapPalette()
                    .load(NeedleGlideExtension.getSongModel(song))
                    .transition(NeedleGlideExtension.getDefaultTransition())
                    .songOptions(song)
                    .into(new NeedleColoredTarget(albumCover) {
                        @Override
                        public void onColorReady(int color) {
                            setColor(color);
                        }
                    });
        }

        void setColor(int color) {
            this.color = color;
            isColorReady = true;
            if (colorReceiver != null) {
                colorReceiver.onColorReady(color, request);
                colorReceiver = null;
            }
        }

        public void receiveColor(ColorReceiver colorReceiver, int request) {
            if (isColorReady) {
                colorReceiver.onColorReady(color, request);
            } else {
                this.colorReceiver = colorReceiver;
                this.request = request;
            }
        }

        public interface ColorReceiver {
            void onColorReady(int color, int request);
        }
    }
}

