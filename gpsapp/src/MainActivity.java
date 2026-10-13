package com.example.gpslogger;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

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

public class MainActivity extends Activity {

    private EditText inputRepo, inputToken, inputPasskey;
    private Spinner spinnerPoints, spinnerSendInterval;
    private Switch switchService;
    private TextView textStatus;

    private static final String PREFS_NAME = "GPSPrefs";
    private static final int PERMISSION_REQUEST_CODE = 100;

    @Override
    protected void onResume() {
        super.onResume();
        updateServiceStatusText();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Build UI programmatically with center gravity
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
layout.setGravity(Gravity.CENTER);
layout.setLayoutParams(new LinearLayout.LayoutParams(
    LinearLayout.LayoutParams.MATCH_PARENT,
    LinearLayout.LayoutParams.MATCH_PARENT
));
int pad = 40;
        layout.setPadding(pad, pad, pad, pad);

        // Title Header
        TextView title = new TextView(this);
        title.setText("Dead Man's Switch Logger");
        title.setTextSize(18);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, 30);
        layout.addView(title);

        inputRepo = new EditText(this);
        inputRepo.setHint("GitHub Repo (e.g., user/repo)");
        inputRepo.setGravity(Gravity.CENTER);
        layout.addView(inputRepo);

        inputToken = new EditText(this);
        inputToken.setHint("GitHub Personal Access Token");
        inputToken.setGravity(Gravity.CENTER);
        layout.addView(inputToken);

        inputPasskey = new EditText(this);
        inputPasskey.setHint("Secret Passkey");
        inputPasskey.setGravity(Gravity.CENTER);
        layout.addView(inputPasskey);

        TextView labelPoints = new TextView(this);
        labelPoints.setText("GPS Points Sampled per Batch:");
        labelPoints.setPadding(0, 10, 0, 5);
        layout.addView(labelPoints);

        spinnerPoints = new Spinner(this);
        layout.addView(spinnerPoints);

        TextView labelSend = new TextView(this);
        labelSend.setText("Send Rate Interval:");
        labelSend.setPadding(0, 10, 0, 5);
        layout.addView(labelSend);

        spinnerSendInterval = new Spinner(this);
        layout.addView(spinnerSendInterval);

        Button btnSave = new Button(this);
        btnSave.setText("Save Configuration");
        layout.addView(btnSave);

        // Test Connection Button
        Button btnTest = new Button(this);
        btnTest.setText("Test Connection & Push");
        layout.addView(btnTest);

        switchService = new Switch(this);
        switchService.setText("Enable Background Service");
        switchService.setPadding(0, 20, 0, 20);
        layout.addView(switchService);

        textStatus = new TextView(this);
        textStatus.setText("Status: Idle");
        textStatus.setGravity(Gravity.CENTER);
        textStatus.setPadding(0, 20, 0, 0);
        layout.addView(textStatus);

        setContentView(layout);

        setupIntervalSpinner();
        loadPreferences();

        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                savePreferences();
                Toast.makeText(MainActivity.this, "Configuration Saved", Toast.LENGTH_SHORT).show();
            }
        });

        // Handle Test Connection click
        btnTest.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                savePreferences();
                textStatus.setText("Testing connection with dummy coordinates...");
                // Push a test ping right now off the main thread
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        testGitHubConnection();
                    }
                }).start();
            }
        });

        switchService.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    if (checkAndRequestPermissions()) {
                        savePreferences();
                        startLocationService();
                    } else {
                        switchService.setChecked(false);
                    }
                } else {
                    stopLocationService();
                }
            }
        });
    }

    private void setupIntervalSpinner() {
        String[] pointsOptions = {"1 point", "2 points", "3 points", "4 points", "5 points", "10 points"};
        ArrayAdapter<String> adapterPoints = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, pointsOptions);
        spinnerPoints.setAdapter(adapterPoints);

        String[] sendIntervals = {"3 mins", "5 mins", "10 mins", "15 mins", "30 mins"};
        ArrayAdapter<String> adapterSend = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, sendIntervals);
        spinnerSendInterval.setAdapter(adapterSend);
    }

    private void savePreferences() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        
        int pointsCount = 1;
        switch (spinnerPoints.getSelectedItemPosition()) {
            case 0: pointsCount = 1; break;
            case 1: pointsCount = 2; break;
            case 2: pointsCount = 3; break;
            case 3: pointsCount = 4; break;
            case 4: pointsCount = 5; break;
            case 5: pointsCount = 10; break;
        }

        int sendRateMinutes = 5;
        switch (spinnerSendInterval.getSelectedItemPosition()) {
            case 0: sendRateMinutes = 3; break;
            case 1: sendRateMinutes = 5; break;
            case 2: sendRateMinutes = 10; break;
            case 3: sendRateMinutes = 15; break;
            case 4: sendRateMinutes = 30; break;
        }

        prefs.edit()
                .putString("repo", inputRepo.getText().toString().trim())
                .putString("token", inputToken.getText().toString().trim())
                .putString("passkey", inputPasskey.getText().toString().trim())
                .putInt("pointsCount", pointsCount)
                .putInt("sendRateMinutes", sendRateMinutes)
                .putBoolean("serviceEnabled", switchService.isChecked())
                .apply();
    }

    private void loadPreferences() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        inputRepo.setText(prefs.getString("repo", ""));
        inputToken.setText(prefs.getString("token", ""));
        inputPasskey.setText(prefs.getString("passkey", ""));

        int pointsCount = prefs.getInt("pointsCount", 1);
        int posPoints = 0;
        if (pointsCount == 1) posPoints = 0;
        else if (pointsCount == 2) posPoints = 1;
        else if (pointsCount == 3) posPoints = 2;
        else if (pointsCount == 4) posPoints = 3;
        else if (pointsCount == 5) posPoints = 4;
        else if (pointsCount == 10) posPoints = 5;
        spinnerPoints.setSelection(posPoints);

        int sendRateMinutes = prefs.getInt("sendRateMinutes", 5);
        int posSend = 1; // default 5 mins
        if (sendRateMinutes == 3) posSend = 0;
        else if (sendRateMinutes == 5) posSend = 1;
        else if (sendRateMinutes == 10) posSend = 2;
        else if (sendRateMinutes == 15) posSend = 3;
        else if (sendRateMinutes == 30) posSend = 4;
        spinnerSendInterval.setSelection(posSend);

        boolean serviceEnabled = prefs.getBoolean("serviceEnabled", false);
        switchService.setChecked(serviceEnabled);
        updateServiceStatusText();
    }

    private boolean isLocationServiceRunning() {
        android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null) {
            for (android.app.ActivityManager.RunningServiceInfo service : am.getRunningServices(Integer.MAX_VALUE)) {
                if (LocationService.class.getName().equals(service.service.getClassName())) {
                    return true;
                }
            }
        }
        return false;
    }

    private void updateServiceStatusText() {
        boolean isRunning = isLocationServiceRunning();
        if (isRunning) {
            textStatus.setText("Status: Background Service Running");
        } else {
            textStatus.setText("Status: Background Service Stopped");
        }
    }

    private boolean checkAndRequestPermissions() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, PERMISSION_REQUEST_CODE);
            return false;
        }
        return true;
    }

    private void testGitHubConnection() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String repo = prefs.getString("repo", "");
        String token = prefs.getString("token", "");
        String passkey = prefs.getString("passkey", "");

        if (repo.isEmpty() || token.isEmpty() || passkey.isEmpty()) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    textStatus.setText("Error: Fill out repo, token, and passkey first.");
                }
            });
            return;
        }

        double lat = 0.0;
        double lng = 0.0;

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (lm != null) {
                Location loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                if (loc == null) {
                    loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                }
                if (loc != null) {
                    lat = loc.getLatitude();
                    lng = loc.getLongitude();
                }
            }
        }

        // If no real location fix is available, notify the user with 'no fix' popup and abort push
        if (lat == 0.0 && lng == 0.0) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(MainActivity.this, "no fix", Toast.LENGTH_SHORT).show();
                    textStatus.setText("Status: no fix");
                }
            });
            return;
        }

        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
        String rawData = String.format(Locale.US, "OK|%.6f,%.6f|TEST-%s", lat, lng, timestamp);

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

        int responseCode = -1;
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
            getConn.setRequestProperty("User-Agent", "Android-GPS-Logger-Test");

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
            putBody.put("message", "Test connection ping: " + timestamp + " (" + pendingQueue.length() + " items)");
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
            putConn.setRequestProperty("User-Agent", "Android-GPS-Logger-Test");
            putConn.setDoOutput(true);

            try (OutputStream os = putConn.getOutputStream()) {
                byte[] input = putBody.toString().getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            responseCode = putConn.getResponseCode();
            putConn.disconnect();

            if (responseCode == 200 || responseCode == 201) {
                uploadSuccess = true;
            }

        } catch (final Exception e) {
            e.printStackTrace();
            uploadSuccess = false;
        }

        if (uploadSuccess) {
            // Upload succeeded, clear the pending queue
            prefs.edit().putString("pending_payloads", "[]").apply();
            final int finalCode = responseCode;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    textStatus.setText("Test Success! Flushed queue & synced map.");
                }
            });
        } else {
            // Upload failed, persist updated queue locally
            prefs.edit().putString("pending_payloads", pendingQueue.toString()).apply();
            final int finalQueueCount = pendingQueue.length();
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    textStatus.setText("Offline/Error! Cached point locally (" + finalQueueCount + " pending)");
                }
            });
        }
    }

    private String encryptWithPasskey(String data, String passkey) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        byte[] keyBytes = md.digest(passkey.getBytes(StandardCharsets.UTF_8));

        SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
        IvParameterSpec ivSpec = new IvParameterSpec(new byte[16]);
        
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec);
        
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        try (java.util.zip.GZIPOutputStream gzos = new java.util.zip.GZIPOutputStream(baos)) {
            gzos.write(data.getBytes(StandardCharsets.UTF_8));
        }
        byte[] compressedBytes = baos.toByteArray();

        byte[] encrypted = cipher.doFinal(compressedBytes);
        return Base64.encodeToString(encrypted, Base64.NO_WRAP);
    }

    private void startLocationService() {
        Intent serviceIntent = new Intent(this, LocationService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        updateServiceStatusText();
    }

    private void stopLocationService() {
        Intent serviceIntent = new Intent(this, LocationService.class);
        stopService(serviceIntent);
        updateServiceStatusText();
    }
}