package com.example.clipboardsync;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends AppCompatActivity {

    private TextView statusTextView;
    private TextView myIpTextView;
    private EditText deviceNameInput;
    private EditText deviceIpInput;
    private Button addDeviceBtn;
    private LinearLayout deviceListLayout;

    private Button manualSendBtn;
    private Button floatingBtn;
    private Button permissionBtn;
    private SharedPreferences prefs;

    private ArrayList<DeviceInfo> savedDevices = new ArrayList<>();
    private ServerSocket serverSocket;
    private boolean isServerRunning = false;

    private static class DeviceInfo {
        String name;
        String ip;
        boolean selected = true;

        DeviceInfo(String name, String ip) {
            this.name = name;
            this.ip = ip;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE);
        loadDevices();

        // Check for Write Permission on older Android versions
        checkStoragePermissions();

        startReceiverServer();

        Intent intent = getIntent();
        if (intent != null && Intent.ACTION_SEND.equals(intent.getAction())) {
            handleIncomingIntent(intent);
            finish();
            return;
        }

        ScrollView scrollView = new ScrollView(this);
        scrollView.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        LinearLayout layout = new LinearLayout(this);
        layout.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        layout.setOrientation(LinearLayout.VERTICAL);

        int padding = dpToPx(24);
        layout.setPadding(padding, padding, padding, padding);
        layout.setGravity(Gravity.CENTER_HORIZONTAL);

        // Title
        TextView title = new TextView(this);
        title.setText("Clipboard Sync");
        title.setTextSize(24f);
        title.setGravity(Gravity.CENTER);
        title.setTextColor(Color.WHITE);

        // STATUS INDICATOR
        statusTextView = new TextView(this);
        statusTextView.setText("Receiver Status: Initializing...");
        statusTextView.setTextSize(14f);
        statusTextView.setGravity(Gravity.CENTER);
        statusTextView.setTextColor(Color.LTGRAY);
        statusTextView.setPadding(0, 0, 0, dpToPx(8));

        // My IP Display
        myIpTextView = new TextView(this);
        myIpTextView.setText("My IP: " + getLocalIpAddress());
        myIpTextView.setTextSize(16f);
        myIpTextView.setGravity(Gravity.CENTER);
        myIpTextView.setTextColor(Color.CYAN);
        myIpTextView.setPadding(0, 0, 0, dpToPx(24));
        myIpTextView.setOnClickListener(v -> myIpTextView.setText("My IP: " + getLocalIpAddress()));

        // --- ADD DEVICE SECTION ---
        TextView addLabel = new TextView(this);
        addLabel.setText("Add Target Device:");
        addLabel.setTextColor(Color.LTGRAY);

        LinearLayout addLayout = new LinearLayout(this);
        addLayout.setOrientation(LinearLayout.HORIZONTAL);
        addLayout.setWeightSum(3);

        deviceNameInput = new EditText(this);
        deviceNameInput.setHint("Name (e.g. PC)");
        deviceNameInput.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        deviceNameInput.setTextColor(Color.WHITE);
        deviceNameInput.setHintTextColor(Color.GRAY);

        deviceIpInput = new EditText(this);
        deviceIpInput.setHint("IP Address");
        deviceIpInput.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        deviceIpInput.setTextColor(Color.WHITE);
        deviceIpInput.setHintTextColor(Color.GRAY);

        addDeviceBtn = new Button(this);
        addDeviceBtn.setText("Add");
        addDeviceBtn.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        addLayout.addView(deviceNameInput);
        addLayout.addView(deviceIpInput);
        addLayout.addView(addDeviceBtn);

        // --- DEVICE LIST SECTION ---
        TextView listLabel = new TextView(this);
        listLabel.setText("Saved Devices (Check to Send):");
        listLabel.setTextColor(Color.LTGRAY);
        listLabel.setPadding(0, dpToPx(16), 0, dpToPx(8));

        deviceListLayout = new LinearLayout(this);
        deviceListLayout.setOrientation(LinearLayout.VERTICAL);
        refreshDeviceListUI();

        // --- ACTIONS ---
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );

        manualSendBtn = new Button(this);
        manualSendBtn.setText("📤 SEND TO SELECTED DEVICES");
        manualSendBtn.setLayoutParams(btnParams);

        floatingBtn = new Button(this);
        floatingBtn.setText("🔵 Enable Floating Button");
        floatingBtn.setLayoutParams(btnParams);

        permissionBtn = new Button(this);
        permissionBtn.setText("Allow Display Over Apps (Required)");
        permissionBtn.setLayoutParams(btnParams);

        layout.addView(title);
        layout.addView(statusTextView);
        layout.addView(myIpTextView);
        layout.addView(addLabel);
        layout.addView(addLayout);
        layout.addView(listLabel);
        layout.addView(deviceListLayout);
        // layout.addView(deviceListLayout); // REMOVED DUPLICATE LINE CAUSING CRASH
        layout.addView(createSpacer(dpToPx(20)));
        layout.addView(manualSendBtn);
        layout.addView(createSpacer(dpToPx(10)));
        layout.addView(floatingBtn);
        layout.addView(createSpacer(dpToPx(10)));
        layout.addView(permissionBtn);

        scrollView.addView(layout);
        setContentView(scrollView);

        // --- LISTENERS ---

        addDeviceBtn.setOnClickListener(v -> {
            String name = deviceNameInput.getText().toString().trim();
            String ip = deviceIpInput.getText().toString().trim();
            if (!name.isEmpty() && !ip.isEmpty()) {
                savedDevices.add(new DeviceInfo(name, ip));
                saveDevices();
                refreshDeviceListUI();
                deviceNameInput.setText("");
                deviceIpInput.setText("");
                try {
                    android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                    imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
                } catch (Exception e) {}
            }
        });

        manualSendBtn.setOnClickListener(v -> performManualSync());

        permissionBtn.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!Settings.canDrawOverlays(this)) {
                    Intent settingsIntent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName()));
                    startActivity(settingsIntent);
                }
            }
        });

        floatingBtn.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Permission Required!", Toast.LENGTH_SHORT).show();
                return;
            }
            Intent serviceIntent = new Intent(MainActivity.this, FloatingButtonService.class);
            startService(serviceIntent);
            moveTaskToBack(true);
        });

        handleIncomingIntent(getIntent());
    }

    private void checkStoragePermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 101);
            }
        }
    }

    // --- DEVICE MANAGEMENT ---

    private void loadDevices() {
        savedDevices.clear();
        Set<String> set = prefs.getStringSet("SavedDevicesList", new HashSet<>());
        for (String s : set) {
            String[] parts = s.split("\\|");
            if (parts.length >= 2) {
                savedDevices.add(new DeviceInfo(parts[0], parts[1]));
            }
        }
    }

    private void saveDevices() {
        Set<String> set = new HashSet<>();
        for (DeviceInfo d : savedDevices) {
            set.add(d.name + "|" + d.ip);
        }
        prefs.edit().putStringSet("SavedDevicesList", set).apply();
    }

    private void refreshDeviceListUI() {
        deviceListLayout.removeAllViews();
        for (int i = 0; i < savedDevices.size(); i++) {
            DeviceInfo device = savedDevices.get(i);
            int index = i;

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, dpToPx(4), 0, dpToPx(4));

            CheckBox checkBox = new CheckBox(this);
            checkBox.setText(device.name + " (" + device.ip + ")");
            checkBox.setTextColor(Color.WHITE);
            checkBox.setChecked(device.selected);
            checkBox.setOnCheckedChangeListener((btn, isChecked) -> device.selected = isChecked);
            LinearLayout.LayoutParams checkParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            checkBox.setLayoutParams(checkParams);

            Button delBtn = new Button(this);
            delBtn.setText("X");
            delBtn.setTextColor(Color.RED);
            delBtn.setOnClickListener(v -> {
                savedDevices.remove(index);
                saveDevices();
                refreshDeviceListUI();
            });

            row.addView(checkBox);
            row.addView(delBtn);
            deviceListLayout.addView(row);
        }
    }

    // --- RECEIVER LOGIC (MOBILE TO MOBILE) ---
    private void startReceiverServer() {
        if (isServerRunning) return;
        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(8888);
                isServerRunning = true;

                new Handler(Looper.getMainLooper()).post(() -> {
                    statusTextView.setText("Receiver Status: Ready (Listening on Port 8888)");
                    statusTextView.setTextColor(Color.GREEN);
                });

                while (isServerRunning) {
                    try {
                        Socket client = serverSocket.accept();
                        handleIncomingData(client);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
                new Handler(Looper.getMainLooper()).post(() -> {
                    statusTextView.setText("Receiver Status: Error (Port 8888 Busy)");
                    statusTextView.setTextColor(Color.RED);
                });
            }
        }).start();
    }

    // Deletes the previously synchronized file to save storage
    private void cleanupOldMedia() {
        String lastUriStr = prefs.getString("LastSyncUri", null);
        if (lastUriStr != null) {
            try {
                Uri lastUri = Uri.parse(lastUriStr);
                getContentResolver().delete(lastUri, null, null);
            } catch (Exception e) {
                // File might already be gone
            }
        }
    }

    // UPDATED: Fixes socket leak and moves IO to background thread
    private void handleIncomingData(Socket client) {
        // Use try-with-resources to ensure the socket is CLOSED after reading
        try (Socket socket = client;
             DataInputStream in = new DataInputStream(socket.getInputStream())) {

            int type = in.readInt();
            int length = in.readInt();
            byte[] buffer = new byte[length];
            in.readFully(buffer);

            // Process data (Background Thread)
            if (type == 1) { // Text
                String text = new String(buffer);
                new Handler(Looper.getMainLooper()).post(() -> {
                    updateStatus(type);
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("Sync", text));
                    Toast.makeText(this, "Received Text!", Toast.LENGTH_SHORT).show();
                });
            }
            else if (type == 2 || type == 3) { // Image or Video
                // Perform heavy I/O (cleanup and save) here, NOT on the main thread
                cleanupOldMedia();
                Uri itemUri = saveMediaToStore(type, buffer);

                if (itemUri != null) {
                    new Handler(Looper.getMainLooper()).post(() -> {
                        updateStatus(type);
                        String mime = (type == 3) ? "video/mp4" : "image/png";
                        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        ClipData.Item clipItem = new ClipData.Item(itemUri);
                        ClipData clip = new ClipData("Sync Media", new String[]{mime}, clipItem);
                        cm.setPrimaryClip(clip);
                        Toast.makeText(this, "Media Synced & Copied!", Toast.LENGTH_LONG).show();
                    });
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            new Handler(Looper.getMainLooper()).post(() ->
                    Toast.makeText(this, "Receive Error: " + e.getMessage(), Toast.LENGTH_SHORT).show()
            );
        }
    }

    private Uri saveMediaToStore(int type, byte[] buffer) {
        try {
            ContentValues values = new ContentValues();
            String timeStamp = String.valueOf(System.currentTimeMillis());
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, "clipboard_sync_" + timeStamp);

            String mime = (type == 3) ? "video/mp4" : "image/png";
            values.put(MediaStore.MediaColumns.MIME_TYPE, mime);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                        (type == 3) ? "Movies/ClipboardSync" : "Pictures/ClipboardSync");
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            }

            Uri collection = (type == 3) ?
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI :
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI;

            Uri itemUri = getContentResolver().insert(collection, values);

            if (itemUri != null) {
                try (OutputStream out = getContentResolver().openOutputStream(itemUri)) {
                    if (out != null) out.write(buffer);
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear();
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    getContentResolver().update(itemUri, values, null, null);
                }

                prefs.edit().putString("LastSyncUri", itemUri.toString()).apply();
                return itemUri;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private void updateStatus(int type) {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        statusTextView.setText("Last Received: " + (type==1?"Text":"File") + " at " + time);
        statusTextView.setTextColor(Color.CYAN);
    }

    private String getLocalIpAddress() {
        try {
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements();) {
                NetworkInterface intf = en.nextElement();
                for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements();) {
                    InetAddress inetAddress = enumIpAddr.nextElement();
                    if (!inetAddress.isLoopbackAddress() && inetAddress instanceof Inet4Address) {
                        return inetAddress.getHostAddress();
                    }
                }
            }
        } catch (Exception ex) { ex.printStackTrace(); }
        return "Unavailable";
    }

    // --- SHARED HELPER METHODS ---

    private int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }

    private android.view.View createSpacer(int heightPx) {
        android.view.View view = new android.view.View(this);
        view.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, heightPx));
        return view;
    }

    @Override
    protected void onResume() {
        super.onResume();
        updatePermissionUI();
    }

    private void updatePermissionUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (Settings.canDrawOverlays(this)) {
                permissionBtn.setText("Display Over Apps: GRANTED ✅");
                permissionBtn.setTextColor(Color.GREEN);
            } else {
                permissionBtn.setText("Allow Display Over Apps (Required) ❌");
                permissionBtn.setTextColor(Color.RED);
            }
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && Intent.ACTION_SEND.equals(intent.getAction())) {
            handleIncomingIntent(intent);
            moveTaskToBack(true);
            return;
        }
        handleIncomingIntent(intent);
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        String type = intent.getType();

        // Auto Send (Floating Button)
        if (intent.getBooleanExtra("AUTO_SEND", false)) {
            new Handler(Looper.getMainLooper()).postDelayed(this::performManualSync, 500);
            return;
        }

        // Share Menu
        if (Intent.ACTION_SEND.equals(action) && type != null) {
            if (savedDevices.isEmpty()) {
                Toast.makeText(getApplicationContext(), "Add a device in the app first!", Toast.LENGTH_LONG).show();
                return;
            }

            if ("text/plain".equals(type)) {
                String sharedText = intent.getStringExtra(Intent.EXTRA_TEXT);
                if (sharedText != null) {
                    Toast.makeText(getApplicationContext(), "Sending text...", Toast.LENGTH_SHORT).show();
                    sendToAllDevices(1, sharedText.getBytes());
                }
            }
            else if (type.startsWith("image/") || type.startsWith("video/")) {
                Uri fileUri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
                if (fileUri != null) {
                    int sendType = type.startsWith("video/") ? 3 : 2;
                    Toast.makeText(getApplicationContext(), "Sending media...", Toast.LENGTH_SHORT).show();
                    new Thread(() -> {
                        byte[] data = getBytesFromUri(fileUri);
                        if (data != null) sendToAllDevices(sendType, data);
                    }).start();
                }
            }
        }
    }

    private void performManualSync() {
        if (savedDevices.isEmpty()) {
            Toast.makeText(getApplicationContext(), "No devices added!", Toast.LENGTH_SHORT).show();
            return;
        }

        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (!clipboard.hasPrimaryClip()) {
            Toast.makeText(getApplicationContext(), "Clipboard is empty", Toast.LENGTH_SHORT).show();
            return;
        }

        ClipData.Item item = clipboard.getPrimaryClip().getItemAt(0);

        new Thread(() -> {
            try {
                int type = 0;
                byte[] data = null;

                if (item.getText() != null) {
                    type = 1;
                    data = item.getText().toString().getBytes("UTF-8");
                } else if (item.getUri() != null) {
                    Uri uri = item.getUri();
                    String mimeType = getContentResolver().getType(uri);
                    if (mimeType != null && mimeType.startsWith("video/")) type = 3;
                    else type = 2;
                    data = getBytesFromUri(uri);
                }

                if (data != null) {
                    sendToAllDevices(type, data);
                }

            } catch (Exception e) {
                new Handler(Looper.getMainLooper()).post(() ->
                        Toast.makeText(getApplicationContext(), "Read Failed: " + e.getMessage(), Toast.LENGTH_LONG).show()
                );
            }
        }).start();
    }

    private void sendToAllDevices(int type, byte[] data) {
        boolean anySent = false;
        for (DeviceInfo device : savedDevices) {
            if (device.selected) {
                sendToIp(device.ip, type, data);
                anySent = true;
            }
        }
        if (!anySent) {
            new Handler(Looper.getMainLooper()).post(() ->
                    Toast.makeText(getApplicationContext(), "No devices selected!", Toast.LENGTH_SHORT).show()
            );
        }
    }

    private void sendToIp(String ip, int type, byte[] data) {
        try {
            Socket socket = new Socket(ip, 8888);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.writeInt(type);
            out.writeInt(data.length);
            out.write(data);
            out.flush();
            socket.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private byte[] getBytesFromUri(Uri uri) {
        try (InputStream is = getContentResolver().openInputStream(uri);
             ByteArrayOutputStream byteBuffer = new ByteArrayOutputStream()) {
            int bufferSize = 1024;
            byte[] buffer = new byte[bufferSize];
            int len;
            while ((len = is.read(buffer)) != -1) {
                byteBuffer.write(buffer, 0, len);
            }
            return byteBuffer.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }
}