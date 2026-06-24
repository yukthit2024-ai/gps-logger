package com.example.gpslocationlogger;

import android.content.Context;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class TrackAdapter extends RecyclerView.Adapter<TrackAdapter.TrackViewHolder> {

    private final Context context;
    private List<TrackItem> trackList;
    private TrackActionListener listener;

    public interface TrackActionListener {
        void onMapClick(TrackItem item);
        void onShareClick(TrackItem item);
        void onRenameClick(TrackItem item);
        void onDeleteClick(TrackItem item);
    }

    public TrackAdapter(Context context, List<TrackItem> trackList, TrackActionListener listener) {
        this.context = context;
        this.trackList = trackList;
        this.listener = listener;
    }

    @NonNull
    @Override
    public TrackViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_track, parent, false);
        return new TrackViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull TrackViewHolder holder, int position) {
        TrackItem trackItem = trackList.get(position);
        
        if (trackItem.isRecordedPoint()) {
            String displayName = trackItem.displayName != null ? trackItem.displayName : trackItem.baseName;
            if (displayName.startsWith("Recorded Point ")) {
                displayName = displayName.substring("Recorded Point ".length());
            } else if ("Recorded Point".equals(displayName)) {
                displayName = "Single Point";
            }
            holder.tvTrackName.setText("📍 " + displayName);
        } else {
            holder.tvTrackName.setText("🏁 " + (trackItem.displayName != null ? trackItem.displayName : trackItem.baseName));
        }
        
        if (trackItem.displayTimestamp != null && !trackItem.displayTimestamp.isEmpty()) {
            holder.tvTrackTimestamp.setText(trackItem.displayTimestamp);
            holder.tvTrackTimestamp.setVisibility(View.VISIBLE);
        } else {
            holder.tvTrackTimestamp.setVisibility(View.GONE);
        }
        
        String extensionsStr = TextUtils.join(", ", trackItem.extensions);
        holder.tvTrackFiles.setText("Files: " + extensionsStr);

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onMapClick(trackItem);
        });

        holder.btnMap.setOnClickListener(v -> {
            if (listener != null) listener.onMapClick(trackItem);
        });
        
        holder.btnShare.setOnClickListener(v -> {
            if (listener != null) listener.onShareClick(trackItem);
        });

        holder.btnRename.setOnClickListener(v -> {
            if (listener != null) listener.onRenameClick(trackItem);
        });
        
        holder.btnDelete.setOnClickListener(v -> {
            if (listener != null) listener.onDeleteClick(trackItem);
        });
    }

    @Override
    public int getItemCount() {
        return trackList.size();
    }

    public static class TrackViewHolder extends RecyclerView.ViewHolder {
        TextView tvTrackName;
        TextView tvTrackTimestamp;
        TextView tvTrackFiles;
        ImageButton btnMap;
        ImageButton btnShare;
        ImageButton btnRename;
        ImageButton btnDelete;

        public TrackViewHolder(@NonNull View itemView) {
            super(itemView);
            tvTrackName = itemView.findViewById(R.id.tvTrackName);
            tvTrackTimestamp = itemView.findViewById(R.id.tvTrackTimestamp);
            tvTrackFiles = itemView.findViewById(R.id.tvTrackFiles);
            btnMap = itemView.findViewById(R.id.btnMap);
            btnShare = itemView.findViewById(R.id.btnShare);
            btnRename = itemView.findViewById(R.id.btnRename);
            btnDelete = itemView.findViewById(R.id.btnDelete);
        }
    }
}
