package com.example.gpslogger;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
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
    private Spinner spinnerInterval;
    private Switch switchService;
    private TextView textStatus;

    private static final String PREFS_NAME = "GPSPrefs";
    private static final int PERMISSION_REQUEST_CODE = 100;

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

        spinnerInterval = new Spinner(this);
        layout.addView(spinnerInterval);

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
        String[] intervals = {"5 mins", "10 mins", "15 mins", "30 mins", "60 mins"};
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, intervals);
        spinnerInterval.setAdapter(adapter);
    }

    private void savePreferences() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        
        int selectedMinutes = 5;
        switch (spinnerInterval.getSelectedItemPosition()) {
            case 0: selectedMinutes = 5; break;
            case 1: selectedMinutes = 10; break;
            case 2: selectedMinutes = 15; break;
            case 3: selectedMinutes = 30; break;
            case 4: selectedMinutes = 60; break;
        }

        prefs.edit()
                .putString("repo", inputRepo.getText().toString().trim())
                .putString("token", inputToken.getText().toString().trim())
                .putString("passkey", inputPasskey.getText().toString().trim())
                .putInt("intervalMinutes", selectedMinutes)
                .apply();
    }

    private void loadPreferences() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        inputRepo.setText(prefs.getString("repo", ""));
        inputToken.setText(prefs.getString("token", ""));
        inputPasskey.setText(prefs.getString("passkey", ""));

        int minutes = prefs.getInt("intervalMinutes", 15);
        int position = 2;
        if (minutes == 5) position = 0;
        else if (minutes == 10) position = 1;
        else if (minutes == 30) position = 3;
        else if (minutes == 60) position = 4;
        spinnerInterval.setSelection(position);
    }

    private boolean checkAndRequestPermissions() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, PERMISSION_REQUEST_CODE);
            return false;
        }
        return true;
    }

    private void testGitHubConnection() {
        try {
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

            // Use dummy test coordinates (e.g., 0.0, 0.0) with a test tag
            String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
            String rawData = "OK|0.0000,0.0000|TEST-" + timestamp;

            String encryptedPayload = encryptWithPasskey(rawData, passkey);
            String apiUrl = "https://api.github.com/repos/" + repo + "/contents/location.json";

            String existingSha = null;
            JSONArray existingArray = new JSONArray();

            HttpURLConnection getConn = (HttpURLConnection) new URL(apiUrl).openConnection();
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

            JSONArray updatedArray = new JSONArray();
            updatedArray.put(encryptedPayload);
            for (int i = 0; i < existingArray.length(); i++) {
                updatedArray.put(existingArray.get(i));
            }

            String newContentBase64 = Base64.encodeToString(updatedArray.toString().getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);

            JSONObject putBody = new JSONObject();
            putBody.put("message", "Test connection ping: " + timestamp);
            putBody.put("content", newContentBase64);
            if (existingSha != null) {
                putBody.put("sha", existingSha);
            }

            HttpURLConnection putConn = (HttpURLConnection) new URL(apiUrl).openConnection();
            putConn.setRequestMethod("PUT");
            putConn.setRequestProperty("Authorization", "Bearer " + token);
            putConn.setRequestProperty("Content-Type", "application/json");
            putConn.setRequestProperty("User-Agent", "Android-GPS-Logger-Test");
            putConn.setDoOutput(true);

            try (OutputStream os = putConn.getOutputStream()) {
                byte[] input = putBody.toString().getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            int responseCode = putConn.getResponseCode();
            putConn.disconnect();

            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (responseCode == 200 || responseCode == 201) {
                        textStatus.setText("Test Success! Check your web map.");
                    } else {
                        textStatus.setText("Failed! GitHub API code: " + responseCode);
                    }
                }
            });

        } catch (final Exception e) {
            e.printStackTrace();
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    textStatus.setText("Test Error: " + e.getMessage());
                }
            });
        }
    }

    private String encryptWithPasskey(String data, String passkey) throws Exception {
        byte[] keyBytes = new byte[32];
        byte[] rawKey = passkey.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(rawKey, 0, keyBytes, 0, Math.min(rawKey.length, keyBytes.length));

        SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
        IvParameterSpec ivSpec = new IvParameterSpec(new byte[16]);
        
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec);
        
        byte[] encrypted = cipher.doFinal(data.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(encrypted, Base64.NO_WRAP);
    }

    private void startLocationService() {
        Intent serviceIntent = new Intent(this, LocationService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        textStatus.setText("Status: Service Running (Idle)");
    }

    private void stopLocationService() {
        Intent serviceIntent = new Intent(this, LocationService.class);
        stopService(serviceIntent);
        textStatus.setText("Status: Service Stopped");
    }
}