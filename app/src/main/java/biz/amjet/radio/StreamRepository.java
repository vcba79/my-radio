package biz.amjet.radio;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.List;

/**
 * Single access point for all stream database operations.
 * Call {@link #seedIfEmpty(Context)} once on first run to populate sample data.
 */
public class StreamRepository {

    // ── Singleton ─────────────────────────────────────────────────────────────

    private static StreamRepository instance;

    public static synchronized StreamRepository getInstance(Context ctx) {
        if (instance == null) {
            instance = new StreamRepository(ctx.getApplicationContext());
        }
        return instance;
    }

    // ── Fields ────────────────────────────────────────────────────────────────

    private final StreamDbHelper dbHelper;

    private StreamRepository(Context context) {
        dbHelper = new StreamDbHelper(context);
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    /** Return all streams ordered by sort_order, then _id. */
    public List<StreamItem> getAll() {
        List<StreamItem> list = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.query(
                StreamDbHelper.TABLE,
                null,
                null, null, null, null,
                StreamDbHelper.COL_SORT + " ASC, " + StreamDbHelper.COL_ID + " ASC"
        );
        while (c.moveToNext()) {
            list.add(fromCursor(c));
        }
        c.close();
        return list;
    }

    /** Search streams by title or description (case-insensitive). */
    public List<StreamItem> search(String query) {
        List<StreamItem> list = new ArrayList<>();
        String q = "%" + query.toLowerCase() + "%";
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.query(
                StreamDbHelper.TABLE,
                null,
                "LOWER(" + StreamDbHelper.COL_TITLE + ") LIKE ? OR LOWER(" + StreamDbHelper.COL_DESC + ") LIKE ?",
                new String[]{q, q},
                null, null,
                StreamDbHelper.COL_SORT + " ASC, " + StreamDbHelper.COL_ID + " ASC"
        );
        while (c.moveToNext()) {
            list.add(fromCursor(c));
        }
        c.close();
        return list;
    }

    // ── Write ─────────────────────────────────────────────────────────────────

    /**
     * Insert a single stream. The {@code id} field of the passed item is ignored;
     * the auto-generated row ID is returned.
     */
    public long insert(StreamItem item) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        return db.insert(StreamDbHelper.TABLE, null, toValues(item));
    }

    /** Insert a batch of streams in a single transaction. */
    public void insertAll(List<StreamItem> items) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.beginTransaction();
        try {
            for (StreamItem item : items) {
                db.insert(StreamDbHelper.TABLE, null, toValues(item));
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** Delete all streams. */
    public void deleteAll() {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.delete(StreamDbHelper.TABLE, null, null);
    }

    /** Delete a stream by its database _id. */
    public void delete(int id) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.delete(StreamDbHelper.TABLE,
                StreamDbHelper.COL_ID + " = ?",
                new String[]{String.valueOf(id)});
    }

    // ── Seed ──────────────────────────────────────────────────────────────────

    /**
     * Populate the database with sample data if the table is empty.
     * Safe to call on every app launch — does nothing if rows already exist.
     */
    public void seedIfEmpty(Context context) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + StreamDbHelper.TABLE, null);
        int count = 0;
        if (c.moveToFirst()) count = c.getInt(0);
        c.close();

        if (count == 0) {
            insertAll(buildSeedData(context));
        }
    }

    /** Returns the hard-coded sample streams. */
    private List<StreamItem> buildSeedData(Context context) {
        List<StreamItem> seeds = new ArrayList<>();
        // id=0 is ignored — DB will assign AUTO-INCREMENT ids
        seeds.add(new StreamItem(0,
                "Big Buck Bunny (HLS)",
                context.getString(R.string.seed_desc_bbb),
                "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
                StreamType.VIDEO, ""));
        seeds.add(new StreamItem(0,
                "Classical Radio",
                context.getString(R.string.seed_desc_classical),
                "https://ice1.somafm.com/dronezone-128-mp3",
                StreamType.AUDIO, ""));
        return seeds;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private StreamItem fromCursor(Cursor c) {
        int    id    = c.getInt(c.getColumnIndexOrThrow(StreamDbHelper.COL_ID));
        String title = c.getString(c.getColumnIndexOrThrow(StreamDbHelper.COL_TITLE));
        String desc  = c.getString(c.getColumnIndexOrThrow(StreamDbHelper.COL_DESC));
        String url   = c.getString(c.getColumnIndexOrThrow(StreamDbHelper.COL_URL));
        String type  = c.getString(c.getColumnIndexOrThrow(StreamDbHelper.COL_TYPE));
        String thumb = c.getString(c.getColumnIndexOrThrow(StreamDbHelper.COL_THUMB));
        return new StreamItem(id, title, desc, url, StreamType.valueOf(type), thumb);
    }

    private ContentValues toValues(StreamItem item) {
        ContentValues cv = new ContentValues();
        cv.put(StreamDbHelper.COL_TITLE, item.getTitle());
        cv.put(StreamDbHelper.COL_DESC,  item.getDescription());
        cv.put(StreamDbHelper.COL_URL,   item.getUrl());
        cv.put(StreamDbHelper.COL_TYPE,  item.getType().name());
        cv.put(StreamDbHelper.COL_THUMB, item.getThumbnailUrl());
        cv.put(StreamDbHelper.COL_SORT,  0);
        return cv;
    }
}
