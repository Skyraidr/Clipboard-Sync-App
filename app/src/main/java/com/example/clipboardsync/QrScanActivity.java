package com.example.clipboardsync;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.media.Image;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Size;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.Preview;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Full-screen camera that scans a pairing QR code (desktop or phone) and returns its content.
 *
 * Tuned for speed:
 *  - ML Kit's on-device (bundled) model, so there is no model download and the first scan is instant
 *  - Only the QR_CODE format is searched for
 *  - Frames are analysed at ~720p (plenty for a QR on a screen, far cheaper than full resolution)
 *  - KEEP_ONLY_LATEST back-pressure: the detector always works on the newest frame, never a backlog
 *  - The result is returned the moment the first valid code is seen
 */
public class QrScanActivity extends AppCompatActivity {

    public static final String EXTRA_QR_PAYLOAD = "qr_payload";

    private static final String DEFAULT_HINT = "Point your camera at a Clipboard Sync QR code (on a computer or another phone)";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean handled = new AtomicBoolean(false);

    private PreviewView previewView;
    private TextView hintText;
    private Button torchBtn;

    private ExecutorService analysisExecutor;
    private BarcodeScanner scanner;
    private ImageAnalysis imageAnalysis;
    private Camera camera;
    private boolean torchOn = false;
    private long lastWrongCodeHintAt = 0;

    private final Runnable resetHint = () -> {
        if (hintText != null) hintText.setText(DEFAULT_HINT);
    };

    private final ActivityResultLauncher<String> cameraPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    startCamera();
                } else {
                    Toast.makeText(this, "Camera permission is needed to scan the QR code. You can allow it in Settings.", Toast.LENGTH_LONG).show();
                    finish();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Create the detector up front so it is ready for the very first camera frame
        scanner = BarcodeScanning.getClient(new BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build());
        analysisExecutor = Executors.newSingleThreadExecutor();

        buildUi();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        previewView = new PreviewView(this);
        root.addView(previewView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        root.addView(new ViewfinderView(this), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = dpToPx(20);
        bottom.setPadding(pad, pad, pad, pad);

        hintText = new TextView(this);
        hintText.setText(DEFAULT_HINT);
        hintText.setTextColor(Color.WHITE);
        hintText.setTextSize(16f);
        hintText.setGravity(Gravity.CENTER);
        bottom.addView(hintText);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER);
        buttons.setPadding(0, dpToPx(16), 0, 0);

        torchBtn = new Button(this);
        torchBtn.setText("🔦 Flashlight");
        torchBtn.setVisibility(View.GONE); // Shown once we know the camera has a flash
        torchBtn.setOnClickListener(v -> toggleTorch());
        buttons.addView(torchBtn);

        Button cancelBtn = new Button(this);
        cancelBtn.setText("Cancel");
        cancelBtn.setOnClickListener(v -> {
            setResult(RESULT_CANCELED);
            finish();
        });
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cancelParams.setMarginStart(dpToPx(12));
        buttons.addView(cancelBtn, cancelParams);

        bottom.addView(buttons);

        FrameLayout.LayoutParams bottomParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        root.addView(bottom, bottomParams);

        // Keep the controls clear of the navigation bar (edge-to-edge is enforced on new Android versions)
        ViewCompat.setOnApplyWindowInsetsListener(bottom, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(pad + bars.left, pad, pad + bars.right, pad + bars.bottom);
            return insets;
        });

        // Tap to focus (helps with very close or far-away screens)
        previewView.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP) {
                focusAt(event.getX(), event.getY());
                v.performClick();
            }
            return true;
        });

        setContentView(root);
    }

    private void startCamera() {
        final ListenableFuture<ProcessCameraProvider> providerFuture = ProcessCameraProvider.getInstance(this);
        providerFuture.addListener(() -> {
            if (isFinishing() || isDestroyed()) return;
            try {
                bindCamera(providerFuture.get());
            } catch (Exception e) {
                Toast.makeText(this, "Could not start camera: " + e.getMessage(), Toast.LENGTH_LONG).show();
                finish();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCamera(ProcessCameraProvider provider) {
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        ResolutionSelector analysisResolution = new ResolutionSelector.Builder()
                .setResolutionStrategy(new ResolutionStrategy(new Size(1280, 720),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                .build();

        imageAnalysis = new ImageAnalysis.Builder()
                .setResolutionSelector(analysisResolution)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();
        imageAnalysis.setAnalyzer(analysisExecutor, this::analyze);

        CameraSelector selector = CameraSelector.DEFAULT_BACK_CAMERA;
        try {
            if (!provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
                    && provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                selector = CameraSelector.DEFAULT_FRONT_CAMERA; // Some tablets only have a front camera
            }
        } catch (Exception ignored) { }

        provider.unbindAll();
        camera = provider.bindToLifecycle(this, selector, preview, imageAnalysis);

        if (camera.getCameraInfo().hasFlashUnit()) {
            torchBtn.setVisibility(View.VISIBLE);
        }
    }

    @OptIn(markerClass = ExperimentalGetImage.class)
    private void analyze(@NonNull ImageProxy imageProxy) {
        Image mediaImage = imageProxy.getImage();
        if (handled.get() || mediaImage == null) {
            imageProxy.close();
            return;
        }
        InputImage image = InputImage.fromMediaImage(mediaImage, imageProxy.getImageInfo().getRotationDegrees());
        try {
            scanner.process(image)
                    .addOnSuccessListener(this::onBarcodes)
                    // Releasing the frame lets CameraX hand us the next (latest) one
                    .addOnCompleteListener(task -> imageProxy.close());
        } catch (Exception e) {
            imageProxy.close();
        }
    }

    // Runs on the main thread
    private void onBarcodes(List<Barcode> barcodes) {
        if (handled.get() || barcodes.isEmpty()) return;
        for (Barcode barcode : barcodes) {
            String raw = barcode.getRawValue();
            if (raw != null && PairingQr.parse(raw) != null) {
                if (handled.compareAndSet(false, true)) {
                    deliverResult(raw);
                }
                return;
            }
        }
        // A QR code was seen, but it isn't ours
        long now = System.currentTimeMillis();
        if (now - lastWrongCodeHintAt > 2000) {
            lastWrongCodeHintAt = now;
            hintText.setText("That's not a Clipboard Sync QR code");
            mainHandler.removeCallbacks(resetHint);
            mainHandler.postDelayed(resetHint, 2000);
        }
    }

    private void deliverResult(String raw) {
        int feedback = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                ? HapticFeedbackConstants.CONFIRM
                : HapticFeedbackConstants.VIRTUAL_KEY;
        previewView.performHapticFeedback(feedback);
        if (imageAnalysis != null) imageAnalysis.clearAnalyzer();

        Intent data = new Intent();
        data.putExtra(EXTRA_QR_PAYLOAD, raw);
        setResult(RESULT_OK, data);
        finish();
    }

    private void toggleTorch() {
        if (camera == null) return;
        torchOn = !torchOn;
        camera.getCameraControl().enableTorch(torchOn);
    }

    private void focusAt(float x, float y) {
        if (camera == null) return;
        MeteringPoint point = previewView.getMeteringPointFactory().createPoint(x, y);
        FocusMeteringAction action = new FocusMeteringAction.Builder(point)
                .setAutoCancelDuration(3, TimeUnit.SECONDS)
                .build();
        camera.getCameraControl().startFocusAndMetering(action);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mainHandler.removeCallbacksAndMessages(null);
        if (imageAnalysis != null) imageAnalysis.clearAnalyzer();
        if (scanner != null) scanner.close();
        if (analysisExecutor != null) analysisExecutor.shutdown();
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    /** Dims everything except a square window in the middle and draws corner brackets around it. */
    private static class ViewfinderView extends View {
        private final Paint dimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint cornerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path dimPath = new Path();
        private final RectF frame = new RectF();
        private final float density;

        ViewfinderView(Context context) {
            super(context);
            density = context.getResources().getDisplayMetrics().density;
            dimPaint.setColor(0x99000000);
            dimPaint.setStyle(Paint.Style.FILL);
            cornerPaint.setColor(Color.WHITE);
            cornerPaint.setStyle(Paint.Style.STROKE);
            cornerPaint.setStrokeWidth(4 * density);
            cornerPaint.setStrokeCap(Paint.Cap.ROUND);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            float side = Math.min(w, h) * 0.7f;
            float left = (w - side) / 2f;
            float top = (h - side) / 2f - h * 0.05f;
            frame.set(left, top, left + side, top + side);
            float radius = 16 * density;

            dimPath.reset();
            dimPath.setFillType(Path.FillType.EVEN_ODD);
            dimPath.addRect(0, 0, w, h, Path.Direction.CW);
            dimPath.addRoundRect(frame, radius, radius, Path.Direction.CW);
            canvas.drawPath(dimPath, dimPaint);

            float len = 28 * density;
            float l = frame.left, t = frame.top, r = frame.right, b = frame.bottom;
            canvas.drawLine(l, t, l + len, t, cornerPaint);
            canvas.drawLine(l, t, l, t + len, cornerPaint);
            canvas.drawLine(r, t, r - len, t, cornerPaint);
            canvas.drawLine(r, t, r, t + len, cornerPaint);
            canvas.drawLine(l, b, l + len, b, cornerPaint);
            canvas.drawLine(l, b, l, b - len, cornerPaint);
            canvas.drawLine(r, b, r - len, b, cornerPaint);
            canvas.drawLine(r, b, r, b - len, cornerPaint);
        }
    }
}
