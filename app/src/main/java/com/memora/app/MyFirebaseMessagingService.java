package com.memora.app;

import android.app.NotificationManager;
import android.content.Context;
import androidx.core.app.NotificationCompat;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

public class MyFirebaseMessagingService extends FirebaseMessagingService {

    private static final String CHANNEL_ID = "memobuild_tasks";

    @Override
    public void onMessageReceived(RemoteMessage remoteMessage) {
        super.onMessageReceived(remoteMessage);

        String title = "Nova solicitação";
        String body  = "";

        // Prefer notification payload, fall back to data payload
        if (remoteMessage.getNotification() != null) {
            if (remoteMessage.getNotification().getTitle() != null)
                title = remoteMessage.getNotification().getTitle();
            if (remoteMessage.getNotification().getBody() != null)
                body = remoteMessage.getNotification().getBody();
        } else if (remoteMessage.getData().size() > 0) {
            if (remoteMessage.getData().containsKey("title"))
                title = remoteMessage.getData().get("title");
            if (remoteMessage.getData().containsKey("body"))
                body = remoteMessage.getData().get("body");
        }

        NotificationReceiver.createChannel(this);

        NotificationCompat.Builder builder =
            new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true);

        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify((int) System.currentTimeMillis(), builder.build());
    }

    @Override
    public void onNewToken(String token) {
        super.onNewToken(token);
        // Save token to SharedPreferences so MainActivity can pick it up
        getSharedPreferences("MemoBuildPrefs", Context.MODE_PRIVATE)
            .edit()
            .putString("fcm_token", token)
            .apply();
    }
}
