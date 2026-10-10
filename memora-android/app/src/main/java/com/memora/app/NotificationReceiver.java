package com.memora.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class NotificationReceiver extends BroadcastReceiver {

    private static final String CHANNEL_ID = "memobuild_tasks";
    private static final String PREFS_NAME = "MemoBuildPrefs";

    @Override
    public void onReceive(Context context, Intent intent) {
        createChannel(context);
        checkAndNotify(context);
    }

    private void checkAndNotify(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String tasksJson = prefs.getString("tasks", "[]");
        String lastNJson = prefs.getString("lastN", "{}");
        String settingsJson = prefs.getString("settings", "{\"alta\":2,\"media\":5,\"baixa\":10}");

        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
        String nowTime = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
        long now = System.currentTimeMillis();

        try {
            JSONArray tasks = new JSONArray(tasksJson);
            JSONObject lastN = new JSONObject(lastNJson);
            JSONObject settings = new JSONObject(settingsJson);

            // Intervals in milliseconds per priority
            long gapAlta  = settings.optLong("alta",  2)  * 60 * 1000L;
            long gapMedia = settings.optLong("media", 5)  * 60 * 1000L;
            long gapBaixa = settings.optLong("baixa", 10) * 60 * 1000L;

            NotificationManager nm = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
            int notifId = (int)(now / 1000);

            for (int i = 0; i < tasks.length(); i++) {
                JSONObject t = tasks.getJSONObject(i);
                if (t.optBoolean("completed", false)) continue;

                String dueDate = t.optString("dueDate", "");
                if (dueDate.isEmpty()) continue;

                boolean dateOk = dueDate.compareTo(today) <= 0;
                String dueTime = t.optString("dueTime", "");
                boolean timeOk = dueTime.isEmpty()
                    || dueDate.compareTo(today) < 0
                    || (dueDate.equals(today) && dueTime.compareTo(nowTime) <= 0);

                if (!dateOk || !timeOk) continue;

                // Determine gap based on priority (supports both pt and en values)
                String priority = t.optString("priority", "med").toLowerCase();
                long gap;
                if (priority.equals("alta") || priority.equals("high")) {
                    gap = gapAlta;
                } else if (priority.equals("baixa") || priority.equals("low")) {
                    gap = gapBaixa;
                } else {
                    gap = gapMedia;
                }

                String id = t.optString("id", "");
                long last = lastN.optLong(id, 0);
                if (now - last < gap) continue;

                boolean overdue = dueDate.compareTo(today) < 0;
                String title = "⏰ Memo Build — " + (overdue ? "Tarefa Atrasada" : "Tarefa Pendente");
                String body = t.optString("title", "");
                String desc = t.optString("description", "");
                if (!desc.isEmpty()) body += "\n" + desc;

                // Priority label in notification
                String prioLabel = (priority.equals("alta") || priority.equals("high")) ? "🔴 Alta" :
                                   (priority.equals("baixa") || priority.equals("low")) ? "🟢 Baixa" : "🟡 Média";
                body += "\nPrioridade: " + prioLabel;

                NotificationCompat.Builder builder =
                    new NotificationCompat.Builder(context, CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentTitle(title)
                        .setContentText(body)
                        .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setAutoCancel(true);

                nm.notify(notifId++, builder.build());
                lastN.put(id, now);
            }

            prefs.edit().putString("lastN", lastN.toString()).apply();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    static void createChannel(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Tarefas Pendentes", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Lembretes automáticos do Memo Build");
            NotificationManager nm = context.getSystemService(NotificationManager.class);
            nm.createNotificationChannel(ch);
        }
    }
}
