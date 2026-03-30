package biz.amjet.radio;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Saves and restores the last-played StreamItem using SharedPreferences.
 *
 * Keys stored:
 *   last_id          int
 *   last_title       String
 *   last_description String
 *   last_url         String
 *   last_type        String  ("VIDEO" | "AUDIO")
 *   last_thumbnail   String
 */
public class LastStreamPrefs {

    private static final String PREFS_NAME = "last_stream";
    private static final String KEY_ID     = "last_id";
    private static final String KEY_TITLE  = "last_title";
    private static final String KEY_DESC   = "last_description";
    private static final String KEY_URL    = "last_url";
    private static final String KEY_TYPE   = "last_type";
    private static final String KEY_THUMB  = "last_thumbnail";

    private LastStreamPrefs() {}

    /** Persist a StreamItem as the last-played stream. */
    public static void save(Context context, StreamItem item) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt   (KEY_ID,    item.getId())
                .putString(KEY_TITLE, item.getTitle())
                .putString(KEY_DESC,  item.getDescription())
                .putString(KEY_URL,   item.getUrl())
                .putString(KEY_TYPE,  item.getType().name())
                .putString(KEY_THUMB, item.getThumbnailUrl())
                .apply();
    }

    /**
     * Load the last-played StreamItem.
     * Returns {@code null} if nothing has been saved yet.
     */
    public static StreamItem load(Context context) {
        SharedPreferences prefs =
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        String url = prefs.getString(KEY_URL, null);
        if (url == null || url.isEmpty()) return null;

        int    id    = prefs.getInt   (KEY_ID,    -1);
        String title = prefs.getString(KEY_TITLE, "");
        String desc  = prefs.getString(KEY_DESC,  "");
        String type  = prefs.getString(KEY_TYPE,  StreamType.AUDIO.name());
        String thumb = prefs.getString(KEY_THUMB, "");

        return new StreamItem(id, title, desc, url, StreamType.valueOf(type), thumb);
    }

    /** Returns true if a last-played stream is stored. */
    public static boolean exists(Context context) {
        String url = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_URL, null);
        return url != null && !url.isEmpty();
    }
}
