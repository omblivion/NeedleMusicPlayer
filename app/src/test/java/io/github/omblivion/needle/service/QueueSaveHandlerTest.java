package io.github.omblivion.needle.service;

import static org.junit.Assert.assertEquals;
import static org.robolectric.Shadows.shadowOf;

import android.database.sqlite.SQLiteException;
import android.os.Looper;

import io.github.omblivion.needle.App;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = App.class)
public class QueueSaveHandlerTest {
    @Test
    public void failedSaveDoesNotKillHandlerOrPreventNextSave() {
        FailingSaveService service = new FailingSaveService();
        QueueSaveHandler handler = new QueueSaveHandler(service, Looper.getMainLooper());
        handler.sendEmptyMessage(QueueSaveHandler.SAVE_QUEUES);
        handler.sendEmptyMessage(QueueSaveHandler.SAVE_QUEUES);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(2, service.attempts);
    }

    private static class FailingSaveService extends MusicService {
        int attempts;
        @Override void saveQueuesImpl() {
            if (++attempts == 1) throw new SQLiteException("Simulated storage failure");
        }
    }
}
