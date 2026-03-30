package biz.amjet.radio;

import android.os.Parcel;
import android.os.Parcelable;

public class StreamItem implements Parcelable {

    private final int id;
    private final String title;
    private final String description;
    private final String url;
    private final StreamType type;
    private final String thumbnailUrl;

    public StreamItem(int id, String title, String description,
                      String url, StreamType type, String thumbnailUrl) {
        this.id = id;
        this.title = title;
        this.description = description;
        this.url = url;
        this.type = type;
        this.thumbnailUrl = thumbnailUrl != null ? thumbnailUrl : "";
    }

    // ── Parcelable ────────────────────────────────────────────────────────────

    protected StreamItem(Parcel in) {
        id = in.readInt();
        title = in.readString();
        description = in.readString();
        url = in.readString();
        type = StreamType.valueOf(in.readString());
        thumbnailUrl = in.readString();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(id);
        dest.writeString(title);
        dest.writeString(description);
        dest.writeString(url);
        dest.writeString(type.name());
        dest.writeString(thumbnailUrl);
    }

    @Override
    public int describeContents() { return 0; }

    public static final Creator<StreamItem> CREATOR = new Creator<StreamItem>() {
        @Override
        public StreamItem createFromParcel(Parcel in) { return new StreamItem(in); }
        @Override
        public StreamItem[] newArray(int size) { return new StreamItem[size]; }
    };

    // ── Getters ───────────────────────────────────────────────────────────────

    public int getId()            { return id; }
    public String getTitle()      { return title; }
    public String getDescription(){ return description; }
    public String getUrl()        { return url; }
    public StreamType getType()   { return type; }
    public String getThumbnailUrl(){ return thumbnailUrl; }
}
