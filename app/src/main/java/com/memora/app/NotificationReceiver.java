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
        // Reschedule next exact alarm so checks keep running in background
        MainActivity.rescheduleAlarm(context);
    }

    private void checkAndNotify(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String tasksJson   = prefs.getString("tasks",   "[]");
        String notifTsJson = prefs.getString("notifTs", "{}");  // unified timestamps (JS+Java)
        String settingsJson = prefs.getString("settings", "{}");

        String today   = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
        String nowTime = new SimpleDateFormat("HH:mm",      Locale.getDefault()).format(new Date());
        long   nowMs   = System.currentTimeMillis();

        try {
            JSONArray  tasks   = new JSONArray(tasksJson);
            JSONObject notifTs = new JSONObject(notifTsJson);
            JSONObject cfg     = new JSONObject(settingsJson);

            // Helper: parse notifCfg entry — supports BOTH formats:
            //   old: { "alta": 2 }           → {type:"min", value:2}
            //   new: { "alta": {type:"min", value:2} }
            long[] gaps = parseGaps(cfg);   // [alta, media, baixa, custom]

            NotificationManager nm = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
            int notifId = (int)(nowMs / 1000);
            boolean changed = false;

            for (int i = 0; i < tasks.length(); i++) {
                JSONObject t = tasks.getJSONObject(i);
                if (t.optBoolean("completed", false)) continue;

                String id      = t.optString("id", "");
                String dueDate = t.optString("dueDate", "");
                String dueTime = t.optString("dueTime", "");

                // ── Determine startMs: when notifications should begin ──
                long startMs;
                if (!dueDate.isEmpty() && dueDate.compareTo(today) < 0) {
                    // Overdue — notify immediately
                    startMs = 0;
                } else if ((!dueDate.isEmpty() && dueDate.equals(today)) || dueDate.isEmpty()) {
                    // Today or no date
                    if (!dueTime.isEmpty()) {
                        // Has specific time — start at that time today
                        startMs = parseTimeToday(today, dueTime);
                    } else {
                        // No time — start from when task was saved (createdAt)
                        startMs = t.optLong("createdAt", 0);
                    }
                } else {
                    // Future date with no time — start from createdAt
                    if (!dueTime.isEmpty()) {
                        // Future date + time — wait until that exact moment
                        startMs = parseTimeOnDate(dueDate, dueTime);
                    } else {
                        startMs = t.optLong("createdAt", 0);
                    }
                }

                if (nowMs < startMs) continue; // not yet time

                // ── Get gap for this priority ──
                String priority = t.optString("priority", "med").toLowerCase();
                String cfgKey = priority.equals("high") || priority.equals("alta") ? "alta" :
                                priority.equals("low")  || priority.equals("baixa") ? "baixa" :
                                priority.equals("custom") ? "custom" : "media";

                long gap = getGap(cfg, cfgKey, gaps);

                // ── Check last notification time ──
                long last = notifTs.optLong(id, 0);
                boolean shouldFire;
                if (last == 0) {
                    shouldFire = true; // never notified — fire immediately
                } else {
                    shouldFire = (nowMs - last) >= gap;
                }

                if (!shouldFire) continue;

                // ── Build and send notification ──
                boolean overdue = !dueDate.isEmpty() && dueDate.compareTo(today) < 0;
                String label = overdue        ? "Tarefa Atrasada" :
                               dueDate.equals(today) ? "Tarefa para Hoje" : "Tarefa Pendente";
                String title = "⏰ Memo Build — " + label;
                String body  = t.optString("title", "Tarefa");
                String desc  = t.optString("description", "");
                if (!desc.isEmpty()) body += "\n" + desc;

                NotificationCompat.Builder builder =
                    new NotificationCompat.Builder(context, CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentTitle(title)
                        .setContentText(body)
                        .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setAutoCancel(true);

                nm.notify(notifId++, builder.build());
                notifTs.put(id, nowMs);
                changed = true;
            }

            if (changed) {
                prefs.edit().putString("notifTs", notifTs.toString()).apply();
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** Parse notifCfg for all priority keys, returning [alta, media, baixa, custom] in ms */
    private long[] parseGaps(JSONObject cfg) {
        return new long[]{
            getGap(cfg, "alta",   null),
            getGap(cfg, "media",  null),
            getGap(cfg, "baixa",  null),
            getGap(cfg, "custom", null)
        };
    }

    /** Get gap in ms for a cfgKey, handling both old (number) and new (object) formats */
    private long getGap(JSONObject cfg, String key, long[] cached) {
        long defaultMin = key.equals("alta") ? 2 : key.equals("baixa") ? 10 : key.equals("custom") ? 15 : 5;
        try {
            Object val = cfg.opt(key);
            if (val == null) return defaultMin * 60 * 1000L;
            if (val instanceof Number) {
                // Old format: { "alta": 2 }
                long minutes = ((Number) val).longValue();
                return Math.max(1, minutes) * 60 * 1000L;
            }
            if (val instanceof JSONObject) {
                // New format: { "alta": { "type": "min", "value": 2 } }
                JSONObject entry = (JSONObject) val;
                String type = entry.optString("type", "min");
                if ("min".equals(type)) {
                    long minutes = entry.optLong("value", defaultMin);
                    return Math.max(1, minutes) * 60 * 1000L;
                } else {
                    // "time" mode — fire once per day; use 24h gap as the interval
                    return 24 * 60 * 60 * 1000L;
                }
            }
        } catch (Exception e) { /* fall through */ }
        return defaultMin * 60 * 1000L;
    }

    /** Parse "HH:mm" time string on today's date into epoch ms */
    private long parseTimeToday(String today, String hhmm) {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
            return sdf.parse(today + " " + hhmm).getTime();
        } catch (Exception e) { return 0; }
    }

    /** Parse "HH:mm" time on a specific date into epoch ms */
    private long parseTimeOnDate(String date, String hhmm) {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
            return sdf.parse(date + " " + hhmm).getTime();
        } catch (Exception e) { return 0; }
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
