package com.example.clipboardsync;

import java.io.File;
import java.nio.ByteBuffer;
import android.provider.OpenableColumns;
import android.os.Environment;
import android.database.Cursor;
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
import android.app.AlertDialog;

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


        Button clearFilesBtn = new Button(this);
        clearFilesBtn.setText("🗑️ CLEAR SAVED FILES");
        clearFilesBtn.setLayoutParams(btnParams);
        clearFilesBtn.setTextColor(Color.parseColor("#ff4444")); // Make it red so it stands out

        clearFilesBtn.setOnClickListener(v -> clearAllSyncedFiles());

// Add it to the layout (e.g., right above the permissionBtn)
        layout.addView(createSpacer(dpToPx(10)));
        layout.addView(clearFilesBtn);

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





        handleIncomingIntent(getIntent());
    }

    private void clearAllSyncedFiles() {
        new Thread(() -> {
            int deletedCount = 0;

            try {
                // Android 10+ (API 29+) uses MediaStore Relative Paths
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    String selection = MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?";
                    String[] selectionArgs = new String[]{"%ClipboardSync%"};

                    // Delete from all media collections
                    deletedCount += getContentResolver().delete(MediaStore.Files.getContentUri("external"), selection, selectionArgs);
                } else {
                    // Fallback for Android 9 and below: Delete via raw File API
                    File downloadsDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "ClipboardSync");
                    File picturesDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "ClipboardSync");
                    File moviesDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "ClipboardSync");

                    File[] dirs = {downloadsDir, picturesDir, moviesDir};
                    for (File dir : dirs) {
                        if (dir.exists() && dir.isDirectory()) {
                            File[] files = dir.listFiles();
                            if (files != null) {
                                for (File f : files) {
                                    if (f.delete()) deletedCount++;
                                }
                            }
                        }
                    }
                }

                final int finalCount = deletedCount;
                new Handler(Looper.getMainLooper()).post(() ->
                        Toast.makeText(MainActivity.this, "Cleared " + finalCount + " synced files", Toast.LENGTH_SHORT).show()
                );

            } catch (Exception e) {
                e.printStackTrace();
                new Handler(Looper.getMainLooper()).post(() ->
                        Toast.makeText(MainActivity.this, "Error clearing files: " + e.getMessage(), Toast.LENGTH_SHORT).show()
                );
            }
        }).start();
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

            // --- ADDED: Edit Button ---
            Button editBtn = new Button(this);
            editBtn.setText("✎"); // Edit Icon/Text
            editBtn.setOnClickListener(v -> showEditDeviceDialog(index));

            Button delBtn = new Button(this);
            delBtn.setText("X");
            delBtn.setTextColor(Color.RED);
            delBtn.setOnClickListener(v -> {
                savedDevices.remove(index);
                saveDevices();
                refreshDeviceListUI();
            });

            row.addView(checkBox);
            row.addView(editBtn); // --- ADDED ---
            row.addView(delBtn);
            deviceListLayout.addView(row);
        }
    }

    private void showEditDeviceDialog(int index) {
        DeviceInfo device = savedDevices.get(index);

        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
        builder.setTitle("Edit Device");

        // Set up the layout for the dialog
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = dpToPx(20);
        layout.setPadding(padding, padding, padding, padding);

        // Input for Name
        final EditText nameInput = new EditText(this);
        nameInput.setText(device.name);
        nameInput.setHint("Device Name");
        layout.addView(nameInput);

        // Input for IP
        final EditText ipInput = new EditText(this);
        ipInput.setText(device.ip);
        ipInput.setHint("IP Address");
        layout.addView(ipInput);

        builder.setView(layout);

        // Save button logic
        builder.setPositiveButton("Save", (dialog, which) -> {
            String newName = nameInput.getText().toString().trim();
            String newIp = ipInput.getText().toString().trim();

            if (!newName.isEmpty() && !newIp.isEmpty()) {
                device.name = newName;
                device.ip = newIp;
                saveDevices();          // Save to SharedPreferences
                refreshDeviceListUI();  // Update the UI
            } else {
                Toast.makeText(MainActivity.this, "Name and IP cannot be empty", Toast.LENGTH_SHORT).show();
            }
        });

        // Cancel button logic
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());

        builder.show();
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
            else if (type == 2 || type == 3 || type == 4) {
                cleanupOldMedia();
                Uri itemUri;

                if (type == 4) {
                    // Parse File Name and Content
                    ByteBuffer bufferWrapper = ByteBuffer.wrap(buffer);
                    int nameLen = bufferWrapper.getInt();
                    byte[] nameBytes = new byte[nameLen];
                    bufferWrapper.get(nameBytes);
                    String fileName = new String(nameBytes);

                    byte[] fileBytes = new byte[buffer.length - 4 - nameLen];
                    bufferWrapper.get(fileBytes);

                    itemUri = saveFileToDownloads(fileName, fileBytes);
                } else {
                    itemUri = saveMediaToStore(type, buffer); // Your existing Image/Video logic
                }

                if (itemUri != null) {
                    new Handler(Looper.getMainLooper()).post(() -> {
                        updateStatus(type);
                        String mime = "*/*";
                        if (type == 3) mime = "video/mp4";
                        if (type == 2) mime = "image/png";

                        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        ClipData clip = new ClipData("Sync Media", new String[]{mime}, new ClipData.Item(itemUri));
                        cm.setPrimaryClip(clip);
                        Toast.makeText(this, (type == 4 ? "File" : "Media") + " Synced & Copied!", Toast.LENGTH_LONG).show();
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

    private Uri saveFileToDownloads(String fileName, byte[] buffer) {
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
            values.put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream");

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ClipboardSync");
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            }

            // Save to Downloads
            Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
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

        if (savedDevices.isEmpty()) {
            Toast.makeText(getApplicationContext(), "Add a device in the app first!", Toast.LENGTH_LONG).show();
            return;
        }

        // --- SINGLE FILE/TEXT SHARE ---
        if (Intent.ACTION_SEND.equals(action) && type != null) {
            if ("text/plain".equals(type)) {
                String sharedText = intent.getStringExtra(Intent.EXTRA_TEXT);
                if (sharedText != null) {
                    Toast.makeText(getApplicationContext(), "Sending text...", Toast.LENGTH_SHORT).show();

                    // FIX: Wrap network call in a background thread!
                    new Thread(() -> {
                        try {
                            sendToAllDevices(1, sharedText.getBytes("UTF-8"));
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }).start();
                }
            } else {
                Uri fileUri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
                if (fileUri != null) {
                    Toast.makeText(getApplicationContext(), "Sending file...", Toast.LENGTH_SHORT).show();
                    processAndSendUri(fileUri, type);
                }
            }
        }

        // --- MULTIPLE FILE SHARE ---
        else if (Intent.ACTION_SEND_MULTIPLE.equals(action) && type != null) {
            ArrayList<Uri> imageUris = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (imageUris != null) {
                Toast.makeText(getApplicationContext(), "Sending " + imageUris.size() + " files...", Toast.LENGTH_SHORT).show();
                for (Uri fileUri : imageUris) {
                    processAndSendUri(fileUri, type);
                }
            }
        }
    }

    // Helper method to keep things clean
    private void processAndSendUri(Uri fileUri, String mimeType) {
        new Thread(() -> {
            byte[] fileBytes = getBytesFromUri(fileUri);
            if (fileBytes != null) {
                int sendType = 4; // Default to Generic File

                // If it's explicitly an image or video, use those types.
                // Otherwise, it gets sent as a Type 4 Generic File (like a PDF).
                if (mimeType != null) {
                    if (mimeType.startsWith("video/")) sendType = 3;
                    else if (mimeType.startsWith("image/")) sendType = 2;
                }

                if (sendType == 4) {
                    String fileName = getFileNameFromUri(fileUri);
                    if (fileName == null) fileName = "sync_file_" + System.currentTimeMillis();

                    // ---> ADD THIS CHECK <---
                    // Ensure PDFs generated on-the-fly by Samsung Notes get the right extension
                    if (mimeType != null && mimeType.equals("application/pdf") && !fileName.toLowerCase().endsWith(".pdf")) {
                        fileName += ".pdf";
                    }

                    try {
                        byte[] nameBytes = fileName.getBytes("UTF-8");
                        ByteBuffer buffer = ByteBuffer.allocate(4 + nameBytes.length + fileBytes.length);
                        buffer.putInt(nameBytes.length);
                        buffer.put(nameBytes);
                        buffer.put(fileBytes);
                        sendToAllDevices(4, buffer.array());
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                } else {
                    sendToAllDevices(sendType, fileBytes);
                }
            }
        }).start();
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
                    byte[] fileBytes = getBytesFromUri(uri);

                    if (fileBytes != null) {
                        if (mimeType != null && mimeType.startsWith("video/")) {
                            sendToAllDevices(3, fileBytes);
                        } else if (mimeType != null && mimeType.startsWith("image/")) {
                            sendToAllDevices(2, fileBytes);
                        } else {
                            // TYPE 4: Generic File (PDF, ZIP, DOCX)
                            String fileName = getFileNameFromUri(uri);
                            if (fileName == null) fileName = "sync_file_" + System.currentTimeMillis();

                            byte[] nameBytes = fileName.getBytes("UTF-8");
                            ByteBuffer buffer = ByteBuffer.allocate(4 + nameBytes.length + fileBytes.length);
                            buffer.putInt(nameBytes.length);
                            buffer.put(nameBytes);
                            buffer.put(fileBytes);

                            sendToAllDevices(4, buffer.array());
                        }
                    }
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

    private String getFileNameFromUri(Uri uri) {
        String result = null;
        if (uri.getScheme().equals("content")) {
            try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (index != -1) result = cursor.getString(index);
                }
            }
        }
        if (result == null) {
            result = uri.getPath();
            int cut = result.lastIndexOf('/');
            if (cut != -1) result = result.substring(cut + 1);
        }
        return result;
    }
}