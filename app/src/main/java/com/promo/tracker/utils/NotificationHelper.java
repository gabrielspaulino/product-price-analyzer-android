package com.promo.tracker.utils;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.promo.tracker.R;
import com.promo.tracker.activities.ProductDetailActivity;
import com.promo.tracker.models.PriceSnapshot;
import com.promo.tracker.models.Product;
import com.promo.tracker.services.GrokSearchService;

public class NotificationHelper {
    public static final String CHANNEL_SALES_ID = "promo_sales";

    public static void createNotificationChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;

        NotificationChannel channel = new NotificationChannel(
                CHANNEL_SALES_ID,
                context.getString(R.string.notification_channel_sales),
                NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription(context.getString(R.string.notification_channel_sales_desc));
        channel.enableVibration(true);
        channel.setShowBadge(true);

        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(channel);
    }

    public static boolean needsRuntimePermission() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU;
    }

    public static boolean canPostNotifications(Context context) {
        if (!needsRuntimePermission()) return true;
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    public static void sendSaleNotification(Context context, Product product, PriceSnapshot snap) {
        if (product == null || snap == null) return;
        if (!canPostNotifications(context)) return;

        Intent intent = new Intent(context, ProductDetailActivity.class);
        intent.putExtra("product_id", product.getId());
        intent.putExtra("product_name", product.getName());
        intent.putExtra("watch_id", product.getWatchId());
        intent.putExtra("current_price", product.getCurrentPrice() != null ? product.getCurrentPrice() : -1.0);
        intent.putExtra("target_price", product.getTargetPrice() != null ? product.getTargetPrice() : -1.0);
        intent.putExtra("last_updated", product.getLastUpdated());
        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context,
                product.getId() != null ? product.getId().hashCode() : 0,
                intent,
                pendingFlags);

        String title = context.getString(R.string.notification_sale_title, product.getName());
        String account = snap.getSourceAccount() != null ? snap.getSourceAccount() : context.getString(R.string.notification_sale_default_source);
        String body = context.getString(
                R.string.notification_sale_body,
                GrokSearchService.formatPrice(snap.getPrice()),
                account);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_SALES_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(body)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH);

        NotificationManagerCompat.from(context).notify(
                product.getId() != null ? product.getId().hashCode() : (int) System.currentTimeMillis(),
                builder.build());
    }

    public static void sendFallbackNotification(Context context, String title, String body) {
        if (title == null && body == null) return;
        if (!canPostNotifications(context)) return;

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_SALES_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title != null ? title : context.getString(R.string.app_name))
                .setContentText(body != null ? body : "")
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH);

        NotificationManagerCompat.from(context).notify(
                (int) System.currentTimeMillis(), builder.build());
    }

    public static void sendRemoteSaleNotification(
            Context context,
            String productId,
            String productName,
            String watchId,
            double price,
            double targetPrice,
            String lastUpdated,
            String sourceAccount
    ) {
        Product product = new Product();
        product.setId(productId);
        product.setName(productName);
        product.setWatchId(watchId);
        product.setCurrentPrice(price > 0 ? price : null);
        product.setTargetPrice(targetPrice > 0 ? targetPrice : null);
        product.setLastUpdated(lastUpdated);

        PriceSnapshot snapshot = new PriceSnapshot();
        snapshot.setPrice(price > 0 ? price : 0);
        snapshot.setSourceAccount(sourceAccount);

        sendSaleNotification(context, product, snapshot);
    }
}
