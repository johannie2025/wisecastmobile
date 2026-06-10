package com.wisesmartchurch.mobile;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.List;

public class PlaylistAdapter extends RecyclerView.Adapter<PlaylistAdapter.ViewHolder> {

    private final List<MainActivity.PlaylistItem> items;
    private final OnItemDeleteListener deleteListener;
    private final OnItemClickListener clickListener;
    private int activeIndex = -1;

    // Interfaces pour communiquer les événements à la MainActivity
    public interface OnItemDeleteListener {
        void onItemDelete(int position);
    }

    public interface OnItemClickListener {
        void onItemClick(int position);
    }

    public PlaylistAdapter(List<MainActivity.PlaylistItem> items, 
                           OnItemDeleteListener deleteListener, 
                           OnItemClickListener clickListener) {
        this.items = items;
        this.deleteListener = deleteListener;
        this.clickListener = clickListener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_playlist, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        MainActivity.PlaylistItem item = items.get(position);
        holder.tvName.setText(item.name);
        holder.tvKind.setText(item.kind.toUpperCase());

        // Assignation d'une icône selon le type de média
        switch (item.kind) {
            case "video":
                holder.ivIcon.setImageResource(android.R.drawable.ic_media_play);
                break;
            case "audio":
                holder.ivIcon.setImageResource(android.R.drawable.ic_menu_save);
                break;
            case "image":
            default:
                holder.ivIcon.setImageResource(android.R.drawable.ic_menu_gallery);
                break;
        }

        // Changement visuel si l'élément est en cours de diffusion/projection
        if (position == activeIndex) {
            holder.itemView.setBackgroundColor(Color.parseColor("#D3E8FF")); // Bleu clair
            holder.tvName.setHasTransientState(true); 
        } else {
            holder.itemView.setBackgroundColor(Color.TRANSPARENT);
        }

        // Gestion du clic simple pour projeter directement
        holder.itemView.setOnClickListener(v -> {
            if (clickListener != null) {
                clickListener.onItemClick(holder.getAdapterPosition());
            }
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    /**
     * Met à jour l'index de l'élément actif (en cours de projection)
     */
    public void setActiveIndex(int index) {
        int oldIndex = this.activeIndex;
        this.activeIndex = index;
        notifyItemChanged(oldIndex);
        notifyItemChanged(activeIndex);
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvName;
        TextView tvKind;
        ImageView ivIcon;

        ViewHolder(View itemView) {
            super(itemView);
            // Ces IDs correspondent au fichier XML item_playlist.xml ci-dessous
            tvName = itemView.findViewById(R.id.tv_item_name);
            tvKind = itemView.findViewById(R.id.tv_item_kind);
            ivIcon = itemView.findViewById(R.id.iv_item_icon);
        }
    }
}