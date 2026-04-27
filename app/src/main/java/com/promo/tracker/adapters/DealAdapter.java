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

public class DealAdapter extends RecyclerView.Adapter<DealAdapter.VH> {

    private final List<PriceSnapshot> items;

    public DealAdapter(List<PriceSnapshot> items) { this.items = items; }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_deal, parent, false);
        return new VH(v);
    }

    @Override public void onBindViewHolder(@NonNull VH h, int pos) { h.bind(items.get(pos)); }
    @Override public int getItemCount() { return items.size(); }

    static class VH extends RecyclerView.ViewHolder {
        TextView tvProductName, tvPrice, tvSource, tvDate, tvExcerpt, tvLink;

        VH(View v) {
            super(v);
            tvProductName = v.findViewById(R.id.tv_product_name);
            tvPrice       = v.findViewById(R.id.tv_price);
            tvSource      = v.findViewById(R.id.tv_source);
            tvDate        = v.findViewById(R.id.tv_date);
            tvExcerpt     = v.findViewById(R.id.tv_excerpt);
            tvLink        = v.findViewById(R.id.tv_link);
        }

        void bind(PriceSnapshot s) {
            String name = s.getProductName();
            tvProductName.setText(name != null ? name : "Produto");

            tvPrice.setText(GrokSearchService.formatPrice(s.getPrice()));

            String src = s.getSourceAccount();
            tvSource.setText(src != null && !src.isEmpty() ? src : "");

            String dateStr = s.getTweetDate() != null ? s.getTweetDate() : s.getCapturedAt();
            if (dateStr != null && !dateStr.isEmpty()) {
                tvDate.setText(formatDate(dateStr));
                tvDate.setVisibility(View.VISIBLE);
            } else {
                tvDate.setVisibility(View.GONE);
            }

            String excerpt = s.getTweetExcerpt();
            if (excerpt != null && !excerpt.isEmpty()) {
                tvExcerpt.setText(excerpt);
                tvExcerpt.setVisibility(View.VISIBLE);
            } else {
                tvExcerpt.setVisibility(View.GONE);
            }

            String link = s.getTweetUrl();
            if (link != null && !link.isEmpty()) {
                tvLink.setText("Ver oferta →");
                tvLink.setVisibility(View.VISIBLE);
                tvLink.setOnClickListener(v ->
                        v.getContext().startActivity(
                                new Intent(Intent.ACTION_VIEW, Uri.parse(link))));
            } else {
                tvLink.setVisibility(View.GONE);
            }
        }

        private String formatDate(String isoUtc) {
            try {
                ZonedDateTime dateTime = parseIso(isoUtc).withZoneSameInstant(ZoneId.systemDefault());
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.getDefault());
                return dateTime.format(formatter);
            } catch (Exception ignored) {
                return isoUtc.length() > 10 ? isoUtc.substring(0, 10) : isoUtc;
            }
        }

        private ZonedDateTime parseIso(String value) {
            try {
                return OffsetDateTime.parse(value).toZonedDateTime();
            } catch (Exception ignored) {
                return Instant.parse(value).atZone(ZoneId.of("UTC"));
            }
        }
    }
}
