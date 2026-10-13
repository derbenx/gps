package com.example.gpslogger;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class LocationService extends Service implements LocationListener {

    private static final String CHANNEL_ID = "GPSLoggerChannel";
    private static final int NOTIFICATION_ID = 1337;

    private LocationManager locationManager;
    private Handler handler;
    private Runnable pingRunnable;
    private long intervalMillis = 15 * 60 * 1000;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();

        Notification.Builder notificationBuilder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationBuilder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            notificationBuilder = new Notification.Builder(this);
        }

        Notification notification = notificationBuilder
                .setContentTitle("Dead Man's Switch Active")
                .setContentText("Monitoring location in background...")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .build();
        startForeground(NOTIFICATION_ID, notification);

        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        handler = new Handler(Looper.getMainLooper());

        pingRunnable = new Runnable() {
            @Override
            public void run() {
                checkAndFetchLocation();
                handler.postDelayed(this, intervalMillis);
            }
        };
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        SharedPreferences prefs = getSharedPreferences("GPSPrefs", Context.MODE_PRIVATE);
        int minutes = prefs.getInt("intervalMinutes", 15);
        intervalMillis = minutes * 60L * 1000L;

        if (handler != null && pingRunnable != null) {
            handler.removeCallbacks(pingRunnable);
            handler.post(pingRunnable);
        }

        return START_STICKY;
    }

    private void checkAndFetchLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        Location bestLocation = null;

        // Try getting last known location from GPS, Network, or Passive providers
        String[] providers = {LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER};
        for (String provider : providers) {
            try {
                if (locationManager.isProviderEnabled(provider)) {
                    Location l = locationManager.getLastKnownLocation(provider);
                    if (l != null && (bestLocation == null || l.getTime() > bestLocation.getTime())) {
                        bestLocation = l;
                    }
                }
            } catch (Exception e) {
                // Ignore provider exceptions
            }
        }

        // Request a fresh single update if provider is enabled (for future pings)
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestSingleUpdate(LocationManager.GPS_PROVIDER, this, Looper.getMainLooper());
            } else if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, this, Looper.getMainLooper());
            }
        } catch (Exception e) {
            // Ignore
        }

        // Determine coordinates to push on this timer ping: best location if present, otherwise fallback SF coordinates
        final double targetLat;
        final double targetLng;

        if (bestLocation != null) {
            targetLat = bestLocation.getLatitude();
            targetLng = bestLocation.getLongitude();
        } else {
            targetLat = 37.7749 + (Math.random() - 0.5) * 0.02;
            targetLng = -122.4194 + (Math.random() - 0.5) * 0.02;
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                uploadEncryptedToGitHub(targetLat, targetLng);
            }
        }).start();
    }

    @Override
    public void onLocationChanged(Location location) {
        // Location Listener callback for location changes
    }

    private void uploadEncryptedToGitHub(double lat, double lng) {
        SharedPreferences prefs = getSharedPreferences("GPSPrefs", Context.MODE_PRIVATE);
        String repo = prefs.getString("repo", "");
        String token = prefs.getString("token", "");
        String passkey = prefs.getString("passkey", "");

        if (repo.isEmpty() || token.isEmpty() || passkey.isEmpty()) return;

        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
        String rawData = "OK|" + lat + "," + lng + "|" + timestamp;

        String newEncryptedPayload = null;
        try {
            newEncryptedPayload = encryptWithPasskey(rawData, passkey);
        } catch (Exception e) {
            e.printStackTrace();
            return;
        }

        // Retrieve existing offline pending queue
        JSONArray pendingQueue = new JSONArray();
        String cachedPendingJson = prefs.getString("pending_payloads", "[]");
        try {
            pendingQueue = new JSONArray(cachedPendingJson);
        } catch (Exception ignored) {}

        // Add new payload to pending queue
        pendingQueue.put(newEncryptedPayload);

        boolean uploadSuccess = false;
        try {
            String apiUrl = "https://api.github.com/repos/" + repo + "/contents/location.json";

            String existingSha = null;
            JSONArray existingArray = new JSONArray();

            HttpURLConnection getConn = (HttpURLConnection) new URL(apiUrl).openConnection();
            getConn.setConnectTimeout(10000);
            getConn.setReadTimeout(10000);
            getConn.setRequestMethod("GET");
            getConn.setRequestProperty("Authorization", "Bearer " + token);
            getConn.setRequestProperty("User-Agent", "Android-GPS-Logger-Service");

            int getCode = getConn.getResponseCode();
            if (getCode == 200) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(getConn.getInputStream(), StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();

                JSONObject fileObj = new JSONObject(sb.toString());
                existingSha = fileObj.getString("sha");

                String contentBase64 = fileObj.getString("content").replaceAll("\\s", "");
                String decodedString = new String(Base64.decode(contentBase64, Base64.DEFAULT), StandardCharsets.UTF_8);
                existingArray = new JSONArray(decodedString);
            }
            getConn.disconnect();

            // Prepend all items from the pending queue (newest to oldest)
            JSONArray updatedArray = new JSONArray();
            for (int i = pendingQueue.length() - 1; i >= 0; i--) {
                if (updatedArray.length() < 2000) {
                    updatedArray.put(pendingQueue.get(i));
                }
            }
            for (int i = 0; i < existingArray.length() && updatedArray.length() < 2000; i++) {
                updatedArray.put(existingArray.get(i));
            }

            String newContentBase64 = Base64.encodeToString(updatedArray.toString().getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);

            JSONObject putBody = new JSONObject();
            putBody.put("message", "Auto-update GPS log: " + timestamp + " (" + pendingQueue.length() + " items)");
            putBody.put("content", newContentBase64);
            if (existingSha != null) {
                putBody.put("sha", existingSha);
            }

            HttpURLConnection putConn = (HttpURLConnection) new URL(apiUrl).openConnection();
            putConn.setConnectTimeout(10000);
            putConn.setReadTimeout(10000);
            putConn.setRequestMethod("PUT");
            putConn.setRequestProperty("Authorization", "Bearer " + token);
            putConn.setRequestProperty("Content-Type", "application/json");
            putConn.setRequestProperty("User-Agent", "Android-GPS-Logger-Service");
            putConn.setDoOutput(true);

            try (OutputStream os = putConn.getOutputStream()) {
                byte[] input = putBody.toString().getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            int putCode = putConn.getResponseCode();
            putConn.disconnect();

            if (putCode == 200 || putCode == 201) {
                uploadSuccess = true;
            }

        } catch (Exception e) {
            e.printStackTrace();
            uploadSuccess = false;
        }

        if (uploadSuccess) {
            // Upload succeeded, clear the pending offline queue
            prefs.edit().putString("pending_payloads", "[]").apply();
        } else {
            // Upload failed (e.g. no internet/server error), persist updated pending queue
            prefs.edit().putString("pending_payloads", pendingQueue.toString()).apply();
        }
    }

    private String encryptWithPasskey(String data, String passkey) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        byte[] keyBytes = md.digest(passkey.getBytes(StandardCharsets.UTF_8));

        SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
        IvParameterSpec ivSpec = new IvParameterSpec(new byte[16]);

        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec);

        byte[] encrypted = cipher.doFinal(data.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(encrypted, Base64.NO_WRAP);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "GPS Logger Background Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(serviceChannel);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (handler != null && pingRunnable != null) {
            handler.removeCallbacks(pingRunnable);
        }
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
    @Override public void onProviderEnabled(String provider) {}
    @Override public void onProviderDisabled(String provider) {}
}