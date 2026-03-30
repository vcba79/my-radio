package biz.amjet.radio;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

public class StreamAdapter extends ListAdapter<StreamItem, StreamAdapter.ViewHolder> {

    public interface OnItemClickListener {
        void onItemClick(StreamItem item);
    }

    public interface OnItemDeleteListener {
        void onItemDelete(StreamItem item);
    }

    private final OnItemClickListener  clickListener;
    private final OnItemDeleteListener deleteListener;

    public StreamAdapter(OnItemClickListener clickListener, OnItemDeleteListener deleteListener) {
        super(DIFF_CALLBACK);
        this.clickListener  = clickListener;
        this.deleteListener = deleteListener;
    }

    private static final DiffUtil.ItemCallback<StreamItem> DIFF_CALLBACK =
            new DiffUtil.ItemCallback<StreamItem>() {
                @Override
                public boolean areItemsTheSame(@NonNull StreamItem a, @NonNull StreamItem b) {
                    return a.getId() == b.getId();
                }
                @Override
                public boolean areContentsTheSame(@NonNull StreamItem a, @NonNull StreamItem b) {
                    return a.getId() == b.getId()
                            && a.getTitle().equals(b.getTitle())
                            && a.getUrl().equals(b.getUrl());
                }
            };

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_stream, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.bind(getItem(position), clickListener, deleteListener);
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        private final TextView    typeIcon;
        private final TextView    title;
        private final TextView    description;
        private final TextView    typeLabel;
        private final ImageButton btnDelete;

        ViewHolder(View itemView) {
            super(itemView);
            typeIcon    = itemView.findViewById(R.id.typeIcon);
            title       = itemView.findViewById(R.id.title);
            description = itemView.findViewById(R.id.description);
            typeLabel   = itemView.findViewById(R.id.typeLabel);
            btnDelete   = itemView.findViewById(R.id.btnDelete);
        }

        void bind(StreamItem item,
                  OnItemClickListener  clickListener,
                  OnItemDeleteListener deleteListener) {
            boolean isVideo = item.getType() == StreamType.VIDEO;
            typeIcon.setText(isVideo ? "\uD83C\uDFAC" : "\uD83C\uDFB5");
            typeLabel.setText(isVideo ? itemView.getContext().getString(R.string.label_video) : itemView.getContext().getString(R.string.label_audio));
            title.setText(item.getTitle());
            description.setText(item.getDescription());
            itemView.setOnClickListener(v -> clickListener.onItemClick(item));
            btnDelete.setOnClickListener(v -> deleteListener.onItemDelete(item));
        }
    }
}
