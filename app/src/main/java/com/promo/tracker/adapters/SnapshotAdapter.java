package com.promo.tracker.adapters;

import android.content.Intent;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.promo.tracker.R;
import com.promo.tracker.models.PriceSnapshot;
import com.promo.tracker.services.GrokSearchService;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

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
        TextView tvPrice, tvSource, tvDate, tvExcerpt, tvLink;

        VH(View v) {
            super(v);
            tvPrice   = v.findViewById(R.id.tv_price);
            tvSource  = v.findViewById(R.id.tv_source);
            tvDate    = v.findViewById(R.id.tv_date);
            tvExcerpt = v.findViewById(R.id.tv_excerpt);
            tvLink    = v.findViewById(R.id.tv_link);
        }

        void bind(PriceSnapshot s) {
            // Price
            tvPrice.setText(GrokSearchService.formatPrice(s.getPrice()));

            // Source account
            String src = s.getSourceAccount();
            tvSource.setText(src != null && !src.isEmpty() ? src : "@xetdaspromocoes");

            String tweetDate = s.getTweetDate();
            if (tweetDate != null && !tweetDate.isEmpty()) {
                tvDate.setText(formatTweetDate(tweetDate));
                tvDate.setVisibility(View.VISIBLE);
            } else {
                tvDate.setVisibility(View.GONE);
            }

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

        private String formatTweetDate(String isoUtc) {
            try {
                if (isDateOnlySentinel(isoUtc)) {
                    return isoUtc.substring(8, 10) + "/" + isoUtc.substring(5, 7) + "/" + isoUtc.substring(0, 4);
                }
                ZonedDateTime dateTime = parseIsoDate(isoUtc).withZoneSameInstant(ZoneId.systemDefault());
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.getDefault());
                return dateTime.format(formatter);
            } catch (Exception ignored) {
                return isoUtc;
            }
        }

        private boolean isDateOnlySentinel(String value) {
            return value != null && value.matches("\\d{4}-\\d{2}-\\d{2}T12:00:00Z");
        }

        private ZonedDateTime parseIsoDate(String value) {
            try {
                return OffsetDateTime.parse(value).toZonedDateTime();
            } catch (Exception ignored) {
                return Instant.parse(value).atZone(ZoneId.of("UTC"));
            }
        }
    }
}
