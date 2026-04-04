package com.owly.pricetracker.adapters;

import android.content.Intent;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.owly.pricetracker.R;
import com.owly.pricetracker.models.PriceSnapshot;
import com.owly.pricetracker.services.SerperApiService;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

public class SnapshotAdapter extends RecyclerView.Adapter<SnapshotAdapter.VH> {

    private final List<PriceSnapshot> items;

    public SnapshotAdapter(List<PriceSnapshot> items) { this.items = items; }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_snapshot, parent, false);
        return new VH(v);
    }

    @Override public void onBindViewHolder(@NonNull VH h, int pos) { h.bind(items.get(pos)); }
    @Override public int getItemCount() { return items.size(); }

    static class VH extends RecyclerView.ViewHolder {
        TextView tvPrice, tvSource, tvExcerpt, tvLink;

        VH(View v) {
            super(v);
            tvPrice   = v.findViewById(R.id.tv_price);
            tvSource  = v.findViewById(R.id.tv_source);
            tvExcerpt = v.findViewById(R.id.tv_excerpt);
            tvLink    = v.findViewById(R.id.tv_link);
        }

        void bind(PriceSnapshot s) {
            // Price
            tvPrice.setText(SerperApiService.formatPrice(s.getPrice()));

            // Source account
            String src = s.getSourceAccount();
            tvSource.setText(src != null && !src.isEmpty() ? src : "@xetdaspromocoes");

            // Excerpt — always show if present (this was the main visibility bug)
            String excerpt = s.getTweetExcerpt();
            if (excerpt != null && !excerpt.isEmpty()) {
                tvExcerpt.setText(excerpt);
                tvExcerpt.setVisibility(View.VISIBLE);
            } else {
                tvExcerpt.setVisibility(View.GONE);
            }

            // "Ver promoção →" link
            String link = s.getTweetUrl();
            if (link != null && !link.isEmpty()) {
                tvLink.setText("Ver promoção →");
                tvLink.setVisibility(View.VISIBLE);
                tvLink.setOnClickListener(v ->
                        v.getContext().startActivity(
                                new Intent(Intent.ACTION_VIEW, Uri.parse(link))));
            } else {
                tvLink.setVisibility(View.GONE);
            }
        }
    }
}
