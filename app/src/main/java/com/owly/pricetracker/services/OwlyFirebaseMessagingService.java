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
        NotificationHelper.sendRemoteSaleNotification(
                this,
                message.getData().get("product_id"),
                message.getData().get("product_name"),
                message.getData().get("watch_id"),
                parseDouble(message.getData().get("price")),
                parseDouble(message.getData().get("target_price")),
                message.getData().get("last_updated"),
                message.getData().get("source_account")
        );
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
