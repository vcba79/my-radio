package biz.amjet.radio;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/**
 * SQLiteOpenHelper that owns the "streams" table schema and migrations.
 *
 * Schema
 * ──────
 * streams
 *   _id         INTEGER PRIMARY KEY AUTOINCREMENT
 *   title       TEXT    NOT NULL
 *   description TEXT    NOT NULL DEFAULT ''
 *   url         TEXT    NOT NULL
 *   type        TEXT    NOT NULL   ('VIDEO' | 'AUDIO')
 *   thumbnail   TEXT    NOT NULL DEFAULT ''
 *   sort_order  INTEGER NOT NULL DEFAULT 0
 */
public class StreamDbHelper extends SQLiteOpenHelper {

    static final String DB_NAME    = "my_radio.db";
    static final int    DB_VERSION = 1;

    // Table & column names (package-private so StreamRepository can reference them)
    static final String TABLE   = "streams";
    static final String COL_ID  = "_id";
    static final String COL_TITLE = "title";
    static final String COL_DESC  = "description";
    static final String COL_URL   = "url";
    static final String COL_TYPE  = "type";
    static final String COL_THUMB = "thumbnail";
    static final String COL_SORT  = "sort_order";

    private static final String SQL_CREATE =
            "CREATE TABLE " + TABLE + " (" +
            COL_ID    + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
            COL_TITLE + " TEXT NOT NULL, " +
            COL_DESC  + " TEXT NOT NULL DEFAULT '', " +
            COL_URL   + " TEXT NOT NULL, " +
            COL_TYPE  + " TEXT NOT NULL, " +
            COL_THUMB + " TEXT NOT NULL DEFAULT '', " +
            COL_SORT  + " INTEGER NOT NULL DEFAULT 0" +
            ");";

    private static final String SQL_DROP = "DROP TABLE IF EXISTS " + TABLE;

    public StreamDbHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(SQL_CREATE);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Simple strategy: drop and recreate on version bump
        db.execSQL(SQL_DROP);
        onCreate(db);
    }
}
