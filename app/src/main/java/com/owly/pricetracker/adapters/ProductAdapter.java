package com.owly.pricetracker.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.textfield.TextInputEditText;
import com.owly.pricetracker.R;
import com.owly.pricetracker.models.Product;
import com.owly.pricetracker.services.GrokSearchService;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

public class ProductAdapter extends RecyclerView.Adapter<ProductAdapter.VH> {

    public interface Listener {
        void onAnalyze(Product p);
        void onRemove(Product p);
        void onCardClick(Product p);
        void onSetTarget(Product p, Double newTarget);
    }

    private final List<Product> items;
    private final Listener listener;

    public ProductAdapter(List<Product> items, Listener listener) {
        this.items = items;
        this.listener = listener;
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_product_card, parent, false);
        return new VH(v);
    }

    @Override public void onBindViewHolder(@NonNull VH h, int pos) { h.bind(items.get(pos)); }
    @Override public int getItemCount() { return items.size(); }

    class VH extends RecyclerView.ViewHolder {
        TextView tvName, tvPrice, tvLastUpdated, tvStatusBadge, tvTargetIndicator;
        LinearLayout layoutTargetIndicator;
        ProgressBar progressAnalyze;
        Button btnAnalyze, btnRemove, btnSetTarget;
        TextInputEditText etTargetPrice;

        VH(View v) {
            super(v);
            tvName              = v.findViewById(R.id.tv_name);
            tvPrice             = v.findViewById(R.id.tv_price);
            tvLastUpdated       = v.findViewById(R.id.tv_last_updated);
            tvStatusBadge       = v.findViewById(R.id.tv_status_badge);
            tvTargetIndicator   = v.findViewById(R.id.tv_target_indicator);
            layoutTargetIndicator = v.findViewById(R.id.layout_target_indicator);
            progressAnalyze     = v.findViewById(R.id.progress_analyze);
            btnAnalyze          = v.findViewById(R.id.btn_analyze);
            btnRemove           = v.findViewById(R.id.btn_remove);
            btnSetTarget        = v.findViewById(R.id.btn_set_target);
            etTargetPrice       = v.findViewById(R.id.et_target_price);
        }

        void bind(Product p) {
            tvName.setText(p.getName());

            // Price
            if (p.getCurrentPrice() != null && p.getCurrentPrice() > 0) {
                tvPrice.setText(GrokSearchService.formatPrice(p.getCurrentPrice()));
            } else {
                tvPrice.setText("—");
            }

            // Last updated
            if (p.getLastUpdated() != null) {
                tvLastUpdated.setText("Atualizado: " + formatDate(p.getLastUpdated()));
                tvLastUpdated.setVisibility(View.VISIBLE);
            } else {
                tvLastUpdated.setVisibility(View.GONE);
            }

            // Target indicator
            if (p.getTargetPrice() != null) {
                String label = "Alvo: " + GrokSearchService.formatPrice(p.getTargetPrice());
                if (p.isTargetReached()) label += " ✓ Atingido!";
                tvTargetIndicator.setText(label);
                tvTargetIndicator.setTextColor(itemView.getContext().getResources()
                        .getColor(p.isTargetReached() ? R.color.price_target_met : R.color.text_secondary, null));
                layoutTargetIndicator.setVisibility(View.VISIBLE);
            } else {
                layoutTargetIndicator.setVisibility(View.GONE);
            }

            // Status badge
            boolean analyzing = p.isAnalyzing();
            progressAnalyze.setVisibility(analyzing ? View.VISIBLE : View.GONE);
            btnAnalyze.setEnabled(!analyzing);

            switch (p.getStatus()) {
                case "success":
                    tvStatusBadge.setText("Atualizado");
                    tvStatusBadge.setBackgroundResource(R.drawable.bg_badge_success);
                    tvStatusBadge.setTextColor(itemView.getContext().getResources()
                            .getColor(R.color.badge_success_text, null));
                    break;
                case "loading":
                    tvStatusBadge.setText("Analisando…");
                    tvStatusBadge.setBackgroundResource(R.drawable.bg_badge_loading);
                    tvStatusBadge.setTextColor(itemView.getContext().getResources()
                            .getColor(R.color.badge_loading_text, null));
                    break;
                case "error":
                    tvStatusBadge.setText("Erro");
                    tvStatusBadge.setBackgroundResource(R.drawable.bg_badge_error);
                    tvStatusBadge.setTextColor(itemView.getContext().getResources()
                            .getColor(R.color.badge_error_text, null));
                    break;
                default:
                    tvStatusBadge.setText("Aguardando");
                    tvStatusBadge.setBackgroundResource(R.drawable.bg_badge_idle);
                    tvStatusBadge.setTextColor(itemView.getContext().getResources()
                            .getColor(R.color.badge_idle_text, null));
                    break;
            }

            // Target price hint is set in XML; clear it if target exists
            if (p.getTargetPrice() != null) {
                etTargetPrice.setHint(GrokSearchService.formatPrice(p.getTargetPrice()));
            } else {
                etTargetPrice.setHint("Preço alvo (R$)");
            }

            // Click listeners
            itemView.setOnClickListener(v -> listener.onCardClick(p));
            btnAnalyze.setOnClickListener(v -> listener.onAnalyze(p));
            btnRemove.setOnClickListener(v -> listener.onRemove(p));
            btnSetTarget.setOnClickListener(v -> {
                String val = etTargetPrice.getText() != null
                        ? etTargetPrice.getText().toString().trim() : "";
                if (val.isEmpty()) {
                    listener.onSetTarget(p, null);
                } else {
                    try {
                        listener.onSetTarget(p, Double.parseDouble(val.replace(",", ".")));
                    } catch (NumberFormatException e) {
                        etTargetPrice.setError("Valor inválido");
                    }
                }
            });
        }

        private String formatDate(String iso) {
            try {
                SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault());
                in.setTimeZone(TimeZone.getTimeZone("UTC"));
                Date date = in.parse(iso.split("\\.")[0].replace("Z", ""));
                return new SimpleDateFormat("dd/MM/yyyy, HH:mm", Locale.getDefault()).format(date);
            } catch (Exception e) { return iso.length() > 10 ? iso.substring(0, 10) : iso; }
        }
    }
}
