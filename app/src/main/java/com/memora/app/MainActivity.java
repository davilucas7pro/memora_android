package com.memora.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import com.onesignal.Continue;
import com.onesignal.OneSignal;
import com.onesignal.debug.LogLevel;
import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity {

    private static final String CHANNEL_ID = "memobuild_tasks";
    private static final String PREFS_NAME = "MemoBuildPrefs";
    private int notifId = 1000;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        NotificationReceiver.createChannel(this);
        requestNotificationPermission();
        scheduleAlarm();
        scheduleRequestCheck();

        // OneSignal — notificações em tempo real com app fechado
        OneSignal.getDebug().setLogLevel(LogLevel.NONE);
        OneSignal.initWithContext(this, "98b29f95-5602-4f52-a32a-b499ce0a467f");
        OneSignal.getNotifications().requestPermission(true, Continue.none());

        WebView web = findViewById(R.id.webview);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);

        web.addJavascriptInterface(new Object() {

            @JavascriptInterface
            public void showNotification(String title, String body) {
                NotificationReceiver.createChannel(MainActivity.this);
                androidx.core.app.NotificationCompat.Builder builder =
                    new androidx.core.app.NotificationCompat.Builder(MainActivity.this, CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentTitle(title)
                        .setContentText(body)
                        .setStyle(new androidx.core.app.NotificationCompat.BigTextStyle().bigText(body))
                        .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                        .setAutoCancel(true);

                android.app.NotificationManager nm = (android.app.NotificationManager)
                    getSystemService(Context.NOTIFICATION_SERVICE);
                nm.notify(notifId++, builder.build());
            }

            @JavascriptInterface
            public void saveTasks(String tasksJson) {
                SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                prefs.edit().putString("tasks", tasksJson).apply();
            }

            @JavascriptInterface
            public void saveSettings(String settingsJson) {
                SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                prefs.edit().putString("settings", settingsJson).apply();
            }

            @JavascriptInterface
            public void saveContacts(String contactsJson) {
                SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                prefs.edit().putString("contacts", contactsJson).apply();
            }

            @JavascriptInterface
            public void saveNotifTs(String notifTsJson) {
                // Shared notification timestamps — read by both JS and Java alarm
                SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                prefs.edit().putString("notifTs", notifTsJson).apply();
            }

            @JavascriptInterface
            public String getNotifTs() {
                SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                return prefs.getString("notifTs", "{}");
            }

        }, "AndroidBridge");

        web.loadUrl("file:///android_asset/index.html");

        // After page loads, push FCM token to Firebase via JS bridge
        web.setWebViewClient(new android.webkit.WebViewClient() {
            @Override
            public void onPageFinished(android.webkit.WebView view, String url) {
                pushFcmTokenToFirebase(view);
            }
        });
    }

    private void pushFcmTokenToFirebase(WebView web) {
        // Try stored token first
        String stored = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString("fcm_token", null);
        if (stored != null) {
            String js = "javascript:saveFcmToken('" + stored.replace("'", "\\'") + "')";
            web.post(() -> web.evaluateJavascript(js, null));
            return;
        }
        // Request fresh token
        com.google.firebase.messaging.FirebaseMessaging.getInstance().getToken()
            .addOnSuccessListener(token -> {
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putString("fcm_token", token).apply();
                String js = "javascript:saveFcmToken('" + token.replace("'", "\\'") + "')";
                web.post(() -> web.evaluateJavascript(js, null));
            });
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1);
            }
        }
    }

    private void scheduleRequestCheck() {
        PeriodicWorkRequest work = new PeriodicWorkRequest.Builder(
                RequestCheckWorker.class, 15, TimeUnit.MINUTES)
                .build();
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "request_check",
                ExistingPeriodicWorkPolicy.KEEP,
                work);
    }

    private void scheduleAlarm() {
        try {
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            Intent intent = new Intent(this, NotificationReceiver.class);
            PendingIntent pi = PendingIntent.getBroadcast(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            // Use setExact for precise timing; NotificationReceiver reschedules itself each fire
            long triggerAt = System.currentTimeMillis() + 60 * 1000L; // first check in 1 min
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                // Fallback if exact alarm permission not granted
                am.setInexactRepeating(AlarmManager.RTC_WAKEUP, triggerAt, 2 * 60 * 1000L, pi);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Called by NotificationReceiver to reschedule the next check
    static void rescheduleAlarm(Context context) {
        try {
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            Intent intent = new Intent(context, NotificationReceiver.class);
            PendingIntent pi = PendingIntent.getBroadcast(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            long triggerAt = System.currentTimeMillis() + 60 * 1000L; // next check in 1 min
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
