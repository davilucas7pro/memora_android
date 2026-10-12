package com.memora.app;

import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.json.JSONObject;
import org.json.JSONArray;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

public class RequestCheckWorker extends Worker {

    private static final String CHANNEL_ID  = "memobuild_tasks";
    private static final String PREFS_NAME  = "MemoBuildPrefs";
    private static final String DB_BASE     =
        "https://memo-build-default-rtdb.firebaseio.com";

    public RequestCheckWorker(@NonNull Context ctx, @NonNull WorkerParameters params) {
        super(ctx, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            SharedPreferences prefs = getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

            // Read contacts saved by JS via AndroidBridge.saveContacts()
            String contactsJson = prefs.getString("contacts", null);
            if (contactsJson == null || contactsJson.equals("[]")) return Result.success();

            JSONArray contacts = new JSONArray(contactsJson);
            NotificationReceiver.createChannel(getApplicationContext());

            for (int i = 0; i < contacts.length(); i++) {
                JSONObject contact = contacts.getJSONObject(i);
                String token      = contact.getString("token");
                String name       = contact.optString("name", "Contato");
                checkContact(prefs, token, name);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return Result.success();
    }

    private void checkContact(SharedPreferences prefs, String token, String name) {
        try {
            // Fetch all requests for this token via Firebase REST API
            String urlStr = DB_BASE + "/requests/" + token + ".json";
            String body   = httpGet(urlStr);
            if (body == null || body.equals("null")) return;

            JSONObject requests = new JSONObject(body);

            // Load previously seen IDs for this token
            Set<String> seenIds = new HashSet<>(
                prefs.getStringSet("seen_" + token, new HashSet<>())
            );

            Set<String> currentIds = new HashSet<>();
            Iterator<String> keys  = requests.keys();

            while (keys.hasNext()) {
                String reqId  = keys.next();
                currentIds.add(reqId);

                if (!seenIds.contains(reqId)) {
                    // New request — check if it's 'nova'
                    JSONObject req = requests.getJSONObject(reqId);
                    String status  = req.optString("status", "nova");
                    if ("nova".equals(status)) {
                        String title = "📋 Nova solicitação — " + name;
                        String reqTitle = req.optString("title", "Sem título");
                        String desc     = req.optString("description", "");
                        String msgBody  = reqTitle + (desc.isEmpty() ? "" : "\n" + desc);
                        showNotification(title, msgBody);
                    }
                }
            }

            // Persist current IDs as the new "seen" set
            prefs.edit().putStringSet("seen_" + token, currentIds).apply();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void showNotification(String title, String body) {
        NotificationCompat.Builder builder =
            new NotificationCompat.Builder(getApplicationContext(), CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true);

        NotificationManager nm = (NotificationManager)
            getApplicationContext().getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify((int) System.currentTimeMillis(), builder.build());
    }

    private String httpGet(String urlStr) {
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);

            BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream())
            );
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
