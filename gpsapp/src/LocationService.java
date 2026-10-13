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

        SharedPreferences prefs = getSharedPreferences("GPSPrefs", Context.MODE_PRIVATE);
        int minutes = prefs.getInt("intervalMinutes", 15);
        intervalMillis = minutes * 60L * 1000L;

        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        handler = new Handler(Looper.getMainLooper());

        pingRunnable = new Runnable() {
            @Override
            public void run() {
                checkAndFetchLocation();
                handler.postDelayed(this, intervalMillis);
            }
        };
        handler.post(pingRunnable);
    }

    private void checkAndFetchLocation() {
        boolean isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
        if (!isGpsEnabled) return;

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            locationManager.requestSingleUpdate(LocationManager.GPS_PROVIDER, this, Looper.getMainLooper());
        }
    }

    @Override
    public void onLocationChanged(Location location) {
        double lat = location.getLatitude();
        double lng = location.getLongitude();

        new Thread(new Runnable() {
    @Override
    public void run() {
        uploadEncryptedToGitHub(lat, lng);
    }
}).start();

    }

    private void uploadEncryptedToGitHub(double lat, double lng) {
        try {
            SharedPreferences prefs = getSharedPreferences("GPSPrefs", Context.MODE_PRIVATE);
            String repo = prefs.getString("repo", "");
            String token = prefs.getString("token", "");
            String passkey = prefs.getString("passkey", "");

            if (repo.isEmpty() || token.isEmpty() || passkey.isEmpty()) return;

            String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
            String rawData = "OK|" + lat + "," + lng + "|" + timestamp;

            String encryptedPayload = encryptWithPasskey(rawData, passkey);
            String apiUrl = "https://api.github.com/repos/" + repo + "/contents/location.json";

            String existingSha = null;
            JSONArray existingArray = new JSONArray();

            HttpURLConnection getConn = (HttpURLConnection) new URL(apiUrl).openConnection();
            getConn.setRequestMethod("GET");
            getConn.setRequestProperty("Authorization", "Bearer " + token);
            getConn.setRequestProperty("User-Agent", "Android-GPS-Logger-Service");

            if (getConn.getResponseCode() == 200) {
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

            JSONArray updatedArray = new JSONArray();
            updatedArray.put(encryptedPayload);
            for (int i = 0; i < existingArray.length() && updatedArray.length() < 2000; i++) {
                updatedArray.put(existingArray.get(i));
            }

            String newContentBase64 = Base64.encodeToString(updatedArray.toString().getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);

            JSONObject putBody = new JSONObject();
            putBody.put("message", "Auto-update GPS log: " + timestamp);
            putBody.put("content", newContentBase64);
            if (existingSha != null) {
                putBody.put("sha", existingSha);
            }

            HttpURLConnection putConn = (HttpURLConnection) new URL(apiUrl).openConnection();
            putConn.setRequestMethod("PUT");
            putConn.setRequestProperty("Authorization", "Bearer " + token);
            putConn.setRequestProperty("Content-Type", "application/json");
            putConn.setRequestProperty("User-Agent", "Android-GPS-Logger-Service");
            putConn.setDoOutput(true);

            try (OutputStream os = putConn.getOutputStream()) {
                byte[] input = putBody.toString().getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            putConn.getResponseCode();
            putConn.disconnect();

        } catch (Exception e) {
            e.printStackTrace();
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