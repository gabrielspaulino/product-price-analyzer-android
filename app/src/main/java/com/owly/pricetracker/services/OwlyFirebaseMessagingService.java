package com.owly.pricetracker.services;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import com.owly.pricetracker.models.User;
import com.owly.pricetracker.utils.NotificationHelper;
import com.owly.pricetracker.utils.PushTokenManager;
import com.owly.pricetracker.utils.SessionManager;

public class OwlyFirebaseMessagingService extends FirebaseMessagingService {

    @Override
    public void onNewToken(String token) {
        super.onNewToken(token);
        User user = SessionManager.getInstance(this).getUser();
        if (user != null) {
            PushTokenManager.registerToken(this, user, token);
        }
    }

    @Override
    public void onMessageReceived(RemoteMessage message) {
        super.onMessageReceived(message);
        NotificationHelper.createNotificationChannel(this);

        java.util.Map<String, String> data = message.getData();

        if (!data.isEmpty()) {
            NotificationHelper.sendRemoteSaleNotification(
                    this,
                    data.get("product_id"),
                    data.get("product_name"),
                    data.get("watch_id"),
                    parseDouble(data.get("price")),
                    parseDouble(data.get("target_price")),
                    data.get("last_updated"),
                    data.get("source_account")
            );
            return;
        }

        RemoteMessage.Notification notification = message.getNotification();
        if (notification != null) {
            NotificationHelper.sendFallbackNotification(
                    this,
                    notification.getTitle(),
                    notification.getBody()
            );
        }
    }

    private double parseDouble(String value) {
        if (value == null || value.isEmpty()) return -1.0;
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ignored) {
            return -1.0;
        }
    }
}
