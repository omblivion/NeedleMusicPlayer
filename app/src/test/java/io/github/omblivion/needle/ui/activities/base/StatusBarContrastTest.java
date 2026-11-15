package io.github.omblivion.needle.ui.activities.base;

import static org.junit.Assert.assertEquals;

import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.widget.FrameLayout;

import io.github.omblivion.needle.App;
import io.github.omblivion.needle.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = App.class)
public class StatusBarContrastTest {
    @Test
    public void edgeToEdgeUsesActualStatusBarBackdropInsteadOfIgnoredWindowColor() {
        try (var controller = Robolectric.buildActivity(BackNavigationTest.TestActivity.class).setup()) {
            var activity = controller.get();
            FrameLayout content = new FrameLayout(activity);
            View statusBar = new View(activity);
            statusBar.setId(R.id.status_bar);
            statusBar.setBackgroundColor(Color.WHITE);
            content.addView(statusBar);
            activity.setContentView(content);

            activity.setStatusbarColor(Color.BLUE);

            assertEquals(Color.BLUE, ((ColorDrawable) statusBar.getBackground()).getColor());
        }
    }
}
