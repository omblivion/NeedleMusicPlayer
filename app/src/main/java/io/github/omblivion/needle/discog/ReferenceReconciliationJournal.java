package io.github.omblivion.needle.discog;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Parcel;
import io.github.omblivion.needle.App;
import io.github.omblivion.needle.model.Song;
import java.util.ArrayList;
import java.util.UUID;
import java.util.function.Supplier;

/** Durable roll-forward intent; store-local operation markers make replay idempotent. */
public final class ReferenceReconciliationJournal extends SQLiteOpenHelper {
    private static final Object LOCK = new Object();
    private static final ThreadLocal<Boolean> INSIDE = ThreadLocal.withInitial(() -> false);

    public static <T> T access(Supplier<T> action) {
        synchronized (LOCK) {
            boolean nested = INSIDE.get();
            INSIDE.set(true);
            try {
                if (!nested && App.getDiscography() != null) {
                    App.getDiscography().recoverReferences();
                }
                return action.get();
            } finally {
                INSIDE.set(nested);
            }
        }
    }

    /**
     * Test seam: runs after the journal intent is written and before any store
     * is updated, so a failure simulates an interrupted publication.
     */
    interface ApplyGate {
        void beforeApply();
        ApplyGate NONE = () -> {};
    }

    static volatile ApplyGate applyGate = ApplyGate.NONE;

    ReferenceReconciliationJournal() {
        super(App.getStaticContext(), "library_reference_journal.db", null, 1);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE pending (id INTEGER PRIMARY KEY CHECK(id=1), operation TEXT NOT NULL,"
                + " base_revision LONG NOT NULL DEFAULT 0, target BLOB NOT NULL)");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS pending");
        onCreate(db);
    }

    String begin(LibrarySnapshot snapshot) {
        Parcel parcel = Parcel.obtain();
        try {
            parcel.writeTypedList(new ArrayList<>(snapshot.songs.values()));
            parcel.writeLongArray(snapshot.confirmedDeleted.stream().mapToLong(Long::longValue).toArray());
            parcel.writeStringList(new ArrayList<>(snapshot.confirmedDeletedPaths));
            ContentValues values = new ContentValues();
            String operation = UUID.randomUUID().toString();
            values.put("id", 1);
            values.put("operation", operation);
            values.put("base_revision", snapshot.baseRevision);
            values.put("target", parcel.marshall());
            getWritableDatabase().insertWithOnConflict("pending", null, values,
                    SQLiteDatabase.CONFLICT_REPLACE);
            return operation;
        } finally {
            parcel.recycle();
        }
    }

    Pending read() {
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT operation,target,base_revision FROM pending WHERE id=1", null)) {
            if (!cursor.moveToFirst()) return null;
            Parcel parcel = Parcel.obtain();
            try {
                byte[] bytes = cursor.getBlob(1);
                parcel.unmarshall(bytes, 0, bytes.length);
                parcel.setDataPosition(0);
                ArrayList<Song> songs = parcel.createTypedArrayList(Song.CREATOR);
                long[] removed = parcel.createLongArray();
                java.util.Set<Long> deleted = new java.util.HashSet<>();
                if (removed != null) for (long id : removed) deleted.add(id);
                ArrayList<String> deletedPaths = parcel.createStringArrayList();
                java.util.Set<String> deletedPathSet = deletedPaths == null
                        ? java.util.Set.of() : new java.util.HashSet<>(deletedPaths);
                return new Pending(cursor.getString(0),
                        new LibrarySnapshot(songs, cursor.getLong(2), deleted, deletedPathSet));
            } finally {
                parcel.recycle();
            }
        }
    }

    void complete() { getWritableDatabase().delete("pending", "id=1", null); }

    static final class Pending {
        final String operation;
        final LibrarySnapshot snapshot;
        Pending(String operation, LibrarySnapshot snapshot) {
            this.operation = operation;
            this.snapshot = snapshot;
        }
    }
}
