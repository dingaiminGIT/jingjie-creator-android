package com.baiyalab.cocamera;

import android.Manifest;
import android.app.Dialog;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.ColorDrawable;
import android.location.Address;
import android.location.Geocoder;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Size;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ConcurrentCamera;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.Preview;
import androidx.camera.core.UseCaseGroup;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.FallbackStrategy;
import androidx.camera.video.MediaStoreOutputOptions;
import androidx.camera.video.PendingRecording;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoRecordEvent;
import androidx.camera.video.VideoCapture;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.text.SimpleDateFormat;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class MainActivity extends AppCompatActivity implements SensorEventListener {
    private static final String[] REQUIRED_PERMISSIONS = {
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_FINE_LOCATION
    };

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat clockFormat = new SimpleDateFormat("yyyy-MM-dd  HH:mm:ss", Locale.CHINA);
    private final ActivityResultLauncher<String[]> permissionLauncher =
        registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> startCamera());

    private FrameLayout root;
    private PreviewView previewView;
    private PreviewView pipPreviewView;
    private GridOverlayView gridView;
    private ScreenLightOverlayView screenLightView;
    private TextView statusView;
    private TextView timerView;
    private TextView watermarkView;
    private ScrollView promptPanel;
    private TextView promptText;
    private Button recordButton;
    private Button dualButton;
    private ProcessCameraProvider cameraProvider;
    private Camera primaryCamera;
    private Preview dualBackPreview;
    private Preview dualFrontPreview;
    private ImageCapture imageCapture;
    private VideoCapture<Recorder> videoCapture;
    private Recording activeRecording;
    private DualCompositeRecorder dualCompositeRecorder;
    private PreRecordManager preRecordManager;
    private boolean frontFacing;
    private boolean dualMode;
    private boolean torchOn;
    private int screenLightMode;
    private float originalScreenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
    private boolean watermarkEnabled;
    private boolean promptRunning;
    private long recordingStartedAt;
    private int promptStep = 2;
    private SpeechRecognizer speechRecognizer;
    private boolean voiceFollowing;
    private boolean speechListening;
    private boolean voiceRestartScheduled;
    private int voiceCommittedChars;
    private float headingDegrees;
    private Location lastLocation;
    private String placeName = "正在获取地名";
    private volatile String currentWatermarkText = "";
    private SensorManager sensorManager;
    private String initialMode = "single";
    private String sceneTitle = "快速拍摄";
    private int dualLayoutMode; // 0: PiP, 1: left/right 50/50, 2: top/bottom 50/50
    private boolean dualSwapped;
    private float pipTouchX;
    private float pipTouchY;
    private float pipStartX;
    private float pipStartY;
    private boolean singleCompositeAvailable;
    private final ExecutorService geocodeExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService analysisExecutor = Executors.newFixedThreadPool(2);
    private final Object dualPhotoLock = new Object();
    private boolean dualPhotoPending;
    private Bitmap dualPhotoBack;
    private Bitmap dualPhotoFront;
    private int dualPhotoLayoutMode;
    private boolean dualPhotoSwapped;
    private RectF dualPhotoPipBounds = new RectF(0.58f, 0.04f, 0.96f, 0.35f);

    private final Runnable clockTick = new Runnable() {
        @Override public void run() {
            updateWatermark();
            if (activeRecording != null || (dualCompositeRecorder != null && dualCompositeRecorder.isRunning()) ||
                (preRecordManager != null && preRecordManager.isCapturing())) {
                long elapsed = (System.currentTimeMillis() - recordingStartedAt) / 1000;
                timerView.setText(String.format(Locale.CHINA, "%02d:%02d", elapsed / 60, elapsed % 60));
            } else {
                timerView.setText("00:00");
            }
            handler.postDelayed(this, 1000);
        }
    };

    private final Runnable promptTick = new Runnable() {
        @Override public void run() {
            if (!promptRunning || promptPanel.getVisibility() != View.VISIBLE) return;
            int maxScroll = Math.max(0, promptText.getHeight() - promptPanel.getHeight() + dp(48));
            int next = promptPanel.getScrollY() + promptStep;
            if (next >= maxScroll) next = 0;
            promptPanel.scrollTo(0, next);
            handler.postDelayed(this, 50);
        }
    };

    private final Runnable voiceRestartTask = () -> {
        voiceRestartScheduled = false;
        listenForPromptSpeech();
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        initialMode = getIntent().getStringExtra("scene_mode");
        if (initialMode == null) initialMode = "single";
        String requestedTitle = getIntent().getStringExtra("scene_title");
        if (requestedTitle != null && !requestedTitle.trim().isEmpty()) sceneTitle = requestedTitle;
        dualLayoutMode = "interview".equals(initialMode) ? 1 : 0;
        buildUi();
        root.post(this::hideSystemBars);
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        requestPermissionsIfNeeded();
        handler.post(clockTick);
    }

    private void hideSystemBars() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
        }
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);

        previewView = new PreviewView(this);
        previewView.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        root.addView(previewView, matchFrame());

        screenLightView = new ScreenLightOverlayView(this);
        root.addView(screenLightView, matchFrame());

        gridView = new GridOverlayView(this);
        gridView.setVisibility(View.GONE);
        root.addView(gridView, matchFrame());

        pipPreviewView = new PreviewView(this);
        pipPreviewView.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        pipPreviewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        GradientDrawable pipBorder = rounded(Color.BLACK, 18, 2, Color.WHITE);
        pipPreviewView.setBackground(pipBorder);
        pipPreviewView.setClipToOutline(true);
        FrameLayout.LayoutParams pipParams = new FrameLayout.LayoutParams(dp(126), dp(188), Gravity.TOP | Gravity.END);
        pipParams.topMargin = dp(64);
        pipParams.rightMargin = dp(16);
        pipPreviewView.setVisibility(View.GONE);
        root.addView(pipPreviewView, pipParams);
        pipPreviewView.setOnTouchListener((view, event) -> {
            if (!dualMode || dualLayoutMode != 0) return false;
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                pipTouchX = event.getRawX();
                pipTouchY = event.getRawY();
                pipStartX = view.getX();
                pipStartY = view.getY();
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_MOVE) {
                float maxX = Math.max(0, root.getWidth() - view.getWidth());
                float maxY = Math.max(dp(76), root.getHeight() - dp(236) - view.getHeight());
                view.setX(Math.max(0, Math.min(maxX, pipStartX + event.getRawX() - pipTouchX)));
                view.setY(Math.max(dp(76), Math.min(maxY, pipStartY + event.getRawY() - pipTouchY)));
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_UP) {
                view.performClick();
                return true;
            }
            return false;
        });

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(16), dp(16), dp(16), dp(8));
        TextView title = label("镜界创作 · " + sceneTitle, 18, Color.WHITE);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        top.addView(title, new LinearLayout.LayoutParams(0, dp(44), 1f));
        timerView = chip("00:00");
        top.addView(timerView);
        Button gridButton = iconButton("网格");
        gridButton.setOnClickListener(v -> gridView.setVisibility(gridView.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        top.addView(gridButton);
        FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(76), Gravity.TOP);
        root.addView(top, topParams);

        watermarkView = label("", 13, Color.WHITE);
        watermarkView.setPadding(dp(10), dp(7), dp(10), dp(7));
        watermarkView.setMaxWidth(dp(340));
        watermarkView.setBackground(rounded(Color.argb(135, 0, 0, 0), 12, 0, 0));
        watermarkView.setVisibility(View.GONE);
        watermarkView.setOnClickListener(v -> {
            placeName = "正在刷新地名";
            updateLastLocation();
            showToast("正在重新定位");
        });
        FrameLayout.LayoutParams watermarkParams = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.START | Gravity.BOTTOM);
        watermarkParams.leftMargin = dp(16);
        watermarkParams.bottomMargin = dp(236);
        root.addView(watermarkView, watermarkParams);

        promptText = label("看着镜头，自然表达。\n\n点击“提词”编辑你的文稿，然后点播放开始滚动。", 24, Color.WHITE);
        promptText.setGravity(Gravity.CENTER);
        promptText.setLineSpacing(dp(8), 1f);
        promptText.setPadding(dp(18), dp(60), dp(18), dp(160));
        promptPanel = new ScrollView(this);
        promptPanel.setVerticalScrollBarEnabled(false);
        promptPanel.setBackground(rounded(Color.argb(120, 0, 0, 0), 18, 1, Color.argb(100, 255, 255, 255)));
        promptPanel.addView(promptText, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        promptPanel.setVisibility(View.GONE);
        FrameLayout.LayoutParams promptParams = new FrameLayout.LayoutParams(dp(310), dp(190), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        promptParams.topMargin = dp(92);
        root.addView(promptPanel, promptParams);

        LinearLayout leftTools = new LinearLayout(this);
        leftTools.setOrientation(LinearLayout.VERTICAL);
        leftTools.setGravity(Gravity.CENTER);
        leftTools.addView(toolButton("切换", v -> switchLens()));
        leftTools.addView(toolButton("布局", v -> showDualLayoutPicker()));
        leftTools.addView(toolButton("补光", v -> toggleTorch()));
        leftTools.addView(toolButton("提词", v -> editPrompt()));
        leftTools.addView(toolButton("水印", v -> toggleWatermark()));
        FrameLayout.LayoutParams leftParams = new FrameLayout.LayoutParams(dp(70), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.START | Gravity.CENTER_VERTICAL);
        leftParams.leftMargin = dp(8);
        root.addView(leftTools, leftParams);

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setGravity(Gravity.CENTER);
        bottom.setPadding(dp(12), dp(6), dp(12), dp(20));
        bottom.setBackground(rounded(Color.argb(155, 0, 0, 0), 24, 0, 0));

        LinearLayout zoomRow = new LinearLayout(this);
        zoomRow.setGravity(Gravity.CENTER);
        zoomRow.addView(zoomButton("0.5×", 0f));
        zoomRow.addView(zoomButton("1×", 0.18f));
        zoomRow.addView(zoomButton("2×", 0.55f));
        bottom.addView(zoomRow, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)));

        LinearLayout captureRow = new LinearLayout(this);
        captureRow.setGravity(Gravity.CENTER);
        Button photoButton = circleButton("拍照", dp(62), Color.WHITE, Color.BLACK);
        photoButton.setOnClickListener(v -> takePhoto());
        captureRow.addView(photoButton);
        recordButton = circleButton("录像", dp(78), Color.rgb(22, 139, 255), Color.WHITE);
        recordButton.setOnClickListener(v -> toggleRecording());
        LinearLayout.LayoutParams recordParams = new LinearLayout.LayoutParams(dp(78), dp(78));
        recordParams.leftMargin = dp(28);
        recordParams.rightMargin = dp(28);
        captureRow.addView(recordButton, recordParams);
        Button galleryButton = circleButton("相册", dp(62), Color.WHITE, Color.BLACK);
        galleryButton.setOnClickListener(v -> openGallery());
        captureRow.addView(galleryButton);
        bottom.addView(captureRow, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(88)));

        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setGravity(Gravity.CENTER);
        Button singleModeButton = modeButton("单摄", v -> setDualMode(false));
        dualButton = modeButton("双摄", v -> setDualMode(true));
        Button promptModeButton = modeButton("提词", v -> editPrompt());
        Button watermarkModeButton = modeButton("水印", v -> toggleWatermark());
        Button[] compactModes = {singleModeButton, dualButton, promptModeButton, watermarkModeButton};
        for (Button button : compactModes) {
            LinearLayout.LayoutParams compactParams = new LinearLayout.LayoutParams(0, dp(38), 1f);
            compactParams.leftMargin = dp(3);
            compactParams.rightMargin = dp(3);
            modeRow.addView(button, compactParams);
        }
        bottom.addView(modeRow, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42)));

        statusView = label("正在初始化相机…", 12, Color.LTGRAY);
        statusView.setGravity(Gravity.CENTER);
        bottom.addView(statusView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(26)));

        FrameLayout.LayoutParams bottomParams = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(226), Gravity.BOTTOM);
        bottomParams.leftMargin = dp(8);
        bottomParams.rightMargin = dp(8);
        bottomParams.bottomMargin = dp(8);
        root.addView(bottom, bottomParams);

        previewView.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP && primaryCamera != null) {
                MeteringPoint point = previewView.getMeteringPointFactory().createPoint(event.getX(), event.getY());
                FocusMeteringAction action = new FocusMeteringAction.Builder(point)
                    .setAutoCancelDuration(3, TimeUnit.SECONDS).build();
                primaryCamera.getCameraControl().startFocusAndMetering(action);
                statusView.setText("已对焦");
            }
            return true;
        });
    }

    private void requestPermissionsIfNeeded() {
        boolean missing = false;
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                missing = true;
                break;
            }
        }
        if (missing) permissionLauncher.launch(REQUIRED_PERMISSIONS); else startCamera();
    }

    private void startCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            statusView.setText("需要相机权限才能使用");
            return;
        }
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                if ("travel".equals(initialMode) || "interview".equals(initialMode)) {
                    bindDualCamera();
                    if ("interview".equals(initialMode)) root.postDelayed(() -> enablePromptPreset(
                        "先请嘉宾做自我介绍。\n\n问题一：这件事的起因是什么？\n\n问题二：最意外的收获是什么？\n\n最后请用一句话总结。"), 500);
                } else {
                    bindSingleCamera();
                    if ("prompt".equals(initialMode)) root.postDelayed(() -> enablePromptPreset(
                        "先说结论，再解释为什么。\n\n每句话短一点，看着镜头自然表达。\n\n最后给观众一个明确行动建议。"), 350);
                    if ("watermark".equals(initialMode)) root.postDelayed(this::toggleWatermark, 350);
                    if ("review".equals(initialMode)) root.postDelayed(() -> {
                        toggleWatermark();
                        enablePromptPreset("先拍门头和环境。\n\n再讲招牌项目、价格和真实体验。\n\n最后总结适合谁、是否值得来。");
                    }, 350);
                    if ("unboxing".equals(initialMode)) root.postDelayed(() -> {
                        gridView.setVisibility(View.VISIBLE);
                        enablePromptPreset("包装外观。\n\n开箱过程。\n\n核心配件和细节。\n\n上手体验与购买建议。");
                    }, 350);
                    if ("prerecord".equals(initialMode)) root.postDelayed(this::startPreRecordBuffer, 350);
                    if ("content".equals(initialMode) || "course".equals(initialMode)) statusView.setText("课件讲解 · 可切换素材与摄像头");
                }
            } catch (Exception error) {
                statusView.setText("相机初始化失败：" + error.getClass().getSimpleName());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindSingleCamera() {
        if (cameraProvider == null) return;
        stopRecordingIfNeeded();
        cameraProvider.unbindAll();
        dualBackPreview = null;
        dualFrontPreview = null;
        pipPreviewView.setVisibility(View.GONE);
        dualMode = false;

        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());
        imageCapture = new ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build();
        Recorder recorder = new Recorder.Builder()
            .setQualitySelector(QualitySelector.fromOrderedList(
                Arrays.asList(Quality.FHD, Quality.HD, Quality.SD),
                FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
            .build();
        videoCapture = VideoCapture.withOutput(recorder);
        ImageAnalysis analysis = new ImageAnalysis.Builder()
            .setTargetResolution(new Size(640, 480))
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setOutputImageRotationEnabled(true)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build();
        analysis.setAnalyzer(analysisExecutor, image -> {
            DualCompositeRecorder composite = dualCompositeRecorder;
            if (composite == null || !composite.isRunning()) image.close(); else composite.offerBack(image);
        });
        CameraSelector selector = frontFacing ? CameraSelector.DEFAULT_FRONT_CAMERA : CameraSelector.DEFAULT_BACK_CAMERA;
        try {
            primaryCamera = cameraProvider.bindToLifecycle(this, selector, preview, imageCapture, videoCapture, analysis);
            singleCompositeAvailable = true;
            statusView.setText(frontFacing ? "前置单摄 · 可拍照录像" : "后置单摄 · 可拍照录像");
        } catch (Exception error) {
            try {
                cameraProvider.unbindAll();
                primaryCamera = cameraProvider.bindToLifecycle(this, selector, preview, imageCapture, videoCapture);
                singleCompositeAvailable = false;
                statusView.setText("相机已开启 · 当前设备只支持预览水印");
            } catch (Exception fallbackError) {
                statusView.setText("无法绑定相机：" + fallbackError.getMessage());
            }
        }
    }

    private void bindDualCamera() {
        bindDualCamera(false);
    }

    private void bindDualCamera(boolean preserveCompositeRecording) {
        if (cameraProvider == null) return;
        if (!preserveCompositeRecording) stopRecordingIfNeeded();
        List<List<CameraInfo>> combinations = cameraProvider.getAvailableConcurrentCameraInfos();
        if (combinations.isEmpty()) {
            dualMode = false;
            showToast("这台设备未开放前后摄并发能力");
            bindSingleCamera();
            return;
        }
        cameraProvider.unbindAll();
        dualBackPreview = new Preview.Builder().build();
        dualFrontPreview = new Preview.Builder().build();
        routeDualPreviews();
        ImageAnalysis backAnalysis = new ImageAnalysis.Builder()
            .setTargetResolution(new Size(640, 480))
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setOutputImageRotationEnabled(true)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build();
        ImageAnalysis frontAnalysis = new ImageAnalysis.Builder()
            .setTargetResolution(new Size(640, 480))
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setOutputImageRotationEnabled(true)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build();
        backAnalysis.setAnalyzer(analysisExecutor, image -> {
            handleDualAnalysisFrame(image, false);
        });
        frontAnalysis.setAnalyzer(analysisExecutor, image -> {
            handleDualAnalysisFrame(image, true);
        });
        UseCaseGroup backGroup = new UseCaseGroup.Builder().addUseCase(dualBackPreview).addUseCase(backAnalysis).build();
        UseCaseGroup frontGroup = new UseCaseGroup.Builder().addUseCase(dualFrontPreview).addUseCase(frontAnalysis).build();
        ConcurrentCamera.SingleCameraConfig backConfig = new ConcurrentCamera.SingleCameraConfig(
            CameraSelector.DEFAULT_BACK_CAMERA, backGroup, this);
        ConcurrentCamera.SingleCameraConfig frontConfig = new ConcurrentCamera.SingleCameraConfig(
            CameraSelector.DEFAULT_FRONT_CAMERA, frontGroup, this);
        try {
            ConcurrentCamera concurrent = cameraProvider.bindToLifecycle(Arrays.asList(backConfig, frontConfig));
            primaryCamera = concurrent.getCameras().get(0);
            imageCapture = null;
            videoCapture = null;
            pipPreviewView.setVisibility(View.VISIBLE);
            applyDualLayout();
            dualMode = true;
            updateDualStatus();
        } catch (Exception error) {
            dualMode = false;
            showToast("双摄启动失败，已回到单摄");
            bindSingleCamera();
        }
    }

    private void routeDualPreviews() {
        if (dualBackPreview == null || dualFrontPreview == null) return;
        dualBackPreview.setSurfaceProvider((dualSwapped ? pipPreviewView : previewView).getSurfaceProvider());
        dualFrontPreview.setSurfaceProvider((dualSwapped ? previewView : pipPreviewView).getSurfaceProvider());
    }

    private void applyDualLayout() {
        pipPreviewView.setTranslationX(0f);
        pipPreviewView.setTranslationY(0f);
        FrameLayout.LayoutParams params;
        if (dualLayoutMode == 2) {
            int usableHeight = Math.max(dp(440), root.getHeight() - dp(76) - dp(234));
            params = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                usableHeight / 2, Gravity.BOTTOM);
            params.bottomMargin = dp(234);
        } else if (dualLayoutMode == 1) {
            params = new FrameLayout.LayoutParams(dp(192), FrameLayout.LayoutParams.MATCH_PARENT, Gravity.TOP | Gravity.END);
            params.topMargin = dp(76);
            params.bottomMargin = dp(234);
            params.rightMargin = 0;
        } else {
            params = new FrameLayout.LayoutParams(dp(126), dp(188), Gravity.TOP | Gravity.END);
            params.topMargin = dp(64);
            params.rightMargin = dp(16);
        }
        pipPreviewView.setLayoutParams(params);
    }

    private void showDualLayoutPicker() {
        if (!dualMode) {
            showToast("布局切换用于双摄模式");
            return;
        }
        if (dualCompositeRecorder != null && dualCompositeRecorder.isRunning()) {
            showToast("请先停止录像再切换布局");
            return;
        }
        Dialog dialog = new Dialog(this);
        LinearLayout sheet = bottomSheet();
        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout headingText = new LinearLayout(this);
        headingText.setOrientation(LinearLayout.VERTICAL);
        headingText.addView(label("选择双摄布局", 23, Color.WHITE));
        headingText.addView(label("预览、照片和视频会保持一致", 13, Color.rgb(145, 150, 158)));
        heading.addView(headingText, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView close = sheetTextButton("关闭", Color.rgb(50, 50, 54));
        close.setOnClickListener(v -> dialog.dismiss());
        heading.addView(close, new LinearLayout.LayoutParams(dp(62), dp(38)));
        sheet.addView(heading);

        String[] icons = {"▣", "◫", "⬒"};
        String[] titles = {"画中画", "左右均分", "上下均分"};
        String[] descriptions = {"小窗可自由拖动", "双方同等展示", "竖屏里上下叙事"};
        LinearLayout row = null;
        for (int i = 0; i < titles.length; i++) {
            // Put the most common PiP choice on its own row, then compare the two
            // equal-split choices side by side. This keeps all options visible and
            // avoids the uneven orphan card produced by a generic two-column grid.
            if (i == 0 || i == 1) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                sheet.addView(row, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(104)));
            }
            final int choice = i;
            boolean selected = dualLayoutMode == i;
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setPadding(dp(14), dp(10), dp(12), dp(10));
            card.setBackground(rounded(selected ? Color.rgb(23, 55, 83) : Color.rgb(31, 31, 34),
                18, selected ? 2 : 1, selected ? Color.rgb(42, 151, 255) : Color.rgb(55, 55, 60)));
            TextView icon = label(icons[i], 24, selected ? Color.rgb(79, 170, 255) : Color.rgb(185, 188, 194));
            card.addView(icon);
            TextView title = label(titles[i] + (selected ? "  · 当前" : ""), 16, Color.WHITE);
            title.setTypeface(null, android.graphics.Typeface.BOLD);
            card.addView(title);
            card.addView(label(descriptions[i], 11, Color.rgb(155, 158, 165)));
            card.setOnClickListener(v -> {
                dualLayoutMode = choice;
                applyDualLayout();
                updateDualStatus();
                dialog.dismiss();
            });
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(0, dp(94), 1f);
            cardParams.setMargins(i == 2 ? dp(5) : 0, dp(10), i == 1 ? dp(5) : 0, 0);
            row.addView(card, cardParams);
        }
        showBottomDialog(dialog, sheet);
    }

    private RectF normalizedPipBounds() {
        int rootWidth = root.getWidth();
        int rootHeight = root.getHeight();
        if (rootWidth <= 0 || rootHeight <= 0) return new RectF(0.58f, 0.04f, 0.96f, 0.35f);
        float left = Math.max(0f, pipPreviewView.getX() / rootWidth);
        float top = Math.max(0f, pipPreviewView.getY() / rootHeight);
        float right = Math.min(1f, (pipPreviewView.getX() + pipPreviewView.getWidth()) / rootWidth);
        float bottom = Math.min(1f, (pipPreviewView.getY() + pipPreviewView.getHeight()) / rootHeight);
        return new RectF(left, top, right, bottom);
    }

    private void updateDualStatus() {
        String cameraOrder = dualSwapped ? "前摄主画面" : "后摄主画面";
        String layout;
        if (dualLayoutMode == 0) layout = "画中画（小窗可拖动）";
        else if (dualLayoutMode == 1) layout = "左右均分 50% / 50%";
        else layout = "上下均分 50% / 50%";
        statusView.setText(cameraOrder + " · " + layout);
    }

    private void handleDualAnalysisFrame(ImageProxy image, boolean front) {
        DualCompositeRecorder recorder = dualCompositeRecorder;
        synchronized (dualPhotoLock) {
            if (!dualPhotoPending) {
                if (recorder == null || !recorder.isRunning()) image.close();
                else if (front) recorder.offerFront(image); else recorder.offerBack(image);
                return;
            }
        }
        try {
            Bitmap bitmap = image.toBitmap();
            int rotation = image.getImageInfo().getRotationDegrees();
            if (rotation != 0) {
                Matrix matrix = new Matrix();
                matrix.postRotate(rotation);
                Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
                if (rotated != bitmap) bitmap.recycle();
                bitmap = rotated;
            }
            Bitmap backToSave = null;
            Bitmap frontToSave = null;
            int layoutToSave = 0;
            boolean swapToSave = false;
            RectF pipToSave = null;
            synchronized (dualPhotoLock) {
                if (!dualPhotoPending) {
                    bitmap.recycle();
                    return;
                }
                if (front) {
                    if (dualPhotoFront != null) dualPhotoFront.recycle();
                    dualPhotoFront = bitmap;
                } else {
                    if (dualPhotoBack != null) dualPhotoBack.recycle();
                    dualPhotoBack = bitmap;
                }
                if (dualPhotoBack != null && dualPhotoFront != null) {
                    backToSave = dualPhotoBack;
                    frontToSave = dualPhotoFront;
                    dualPhotoBack = null;
                    dualPhotoFront = null;
                    dualPhotoPending = false;
                    layoutToSave = dualPhotoLayoutMode;
                    swapToSave = dualPhotoSwapped;
                    pipToSave = new RectF(dualPhotoPipBounds);
                }
            }
            if (backToSave != null) {
                Bitmap savedBack = backToSave;
                Bitmap savedFront = frontToSave;
                int savedLayout = layoutToSave;
                boolean savedSwap = swapToSave;
                RectF savedPip = pipToSave;
                analysisExecutor.execute(() -> saveDualPhoto(savedBack, savedFront, savedLayout, savedSwap, savedPip));
            }
        } catch (RuntimeException error) {
            synchronized (dualPhotoLock) { dualPhotoPending = false; }
            handler.post(() -> showToast("双摄拍照失败：" + error.getMessage()));
        } finally {
            image.close();
        }
    }

    private void requestDualPhoto() {
        if (dualCompositeRecorder != null && dualCompositeRecorder.isRunning()) {
            showToast("请先停止录像，再拍摄双摄合成照片");
            return;
        }
        synchronized (dualPhotoLock) {
            if (dualPhotoPending) return;
            dualPhotoPending = true;
            if (dualPhotoBack != null) dualPhotoBack.recycle();
            if (dualPhotoFront != null) dualPhotoFront.recycle();
            dualPhotoBack = null;
            dualPhotoFront = null;
            dualPhotoLayoutMode = dualLayoutMode;
            dualPhotoSwapped = dualSwapped;
            dualPhotoPipBounds = normalizedPipBounds();
        }
        statusView.setText("正在合成双摄照片…");
        handler.postDelayed(() -> {
            synchronized (dualPhotoLock) {
                if (!dualPhotoPending) return;
                dualPhotoPending = false;
            }
            showToast("没有同时取得两个镜头画面，请重试");
        }, 2500);
    }

    private void saveDualPhoto(Bitmap physicalBack, Bitmap physicalFront, int layoutMode,
                               boolean swapped, RectF normalizedPip) {
        Bitmap output = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        canvas.drawColor(Color.BLACK);
        Bitmap primary = swapped ? physicalFront : physicalBack;
        Bitmap secondary = swapped ? physicalBack : physicalFront;
        boolean primaryMirror = swapped;
        boolean secondaryMirror = !swapped;
        float ratio = layoutMode == 1 ? 0.5f : (layoutMode == 2 ? -0.5f : 0f);
        if (ratio > 0) {
            float divider = output.getWidth() * ratio;
            drawPhotoCenterCrop(canvas, primary, new RectF(0, 0, divider, output.getHeight()), primaryMirror, 0);
            drawPhotoCenterCrop(canvas, secondary, new RectF(divider, 0, output.getWidth(), output.getHeight()), secondaryMirror, 0);
        } else if (ratio < 0) {
            float divider = output.getHeight() * -ratio;
            drawPhotoCenterCrop(canvas, primary, new RectF(0, 0, output.getWidth(), divider), primaryMirror, 0);
            drawPhotoCenterCrop(canvas, secondary, new RectF(0, divider, output.getWidth(), output.getHeight()), secondaryMirror, 0);
        } else {
            drawPhotoCenterCrop(canvas, primary, new RectF(0, 0, output.getWidth(), output.getHeight()), primaryMirror, 0);
            RectF sourceBounds = normalizedPip == null ? new RectF(0.58f, 0.04f, 0.96f, 0.35f) : normalizedPip;
            RectF pip = new RectF(sourceBounds.left * output.getWidth(), sourceBounds.top * output.getHeight(),
                sourceBounds.right * output.getWidth(), sourceBounds.bottom * output.getHeight());
            Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
            border.setColor(Color.WHITE);
            canvas.drawRoundRect(new RectF(pip.left - 8, pip.top - 8, pip.right + 8, pip.bottom + 8), 40, 40, border);
            drawPhotoCenterCrop(canvas, secondary, pip, secondaryMirror, 34);
        }
        if (watermarkEnabled && !currentWatermarkText.isEmpty()) drawPhotoWatermark(canvas, currentWatermarkText);
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, "JingJie_DualPhoto_" +
                new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(new Date()));
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/镜界创作");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
            android.net.Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IllegalStateException("无法创建图片");
            try (OutputStream stream = getContentResolver().openOutputStream(uri)) {
                if (stream == null || !output.compress(Bitmap.CompressFormat.JPEG, 95, stream)) {
                    throw new IllegalStateException("图片编码失败");
                }
            }
            ContentValues ready = new ContentValues();
            ready.put(MediaStore.Images.Media.IS_PENDING, 0);
            getContentResolver().update(uri, ready, null, null);
            handler.post(() -> {
                statusView.setText("双摄合成照片已保存");
                showToast("照片已保存到 Pictures/镜界创作");
            });
        } catch (Exception error) {
            handler.post(() -> showToast("保存双摄照片失败：" + error.getMessage()));
        } finally {
            physicalBack.recycle();
            physicalFront.recycle();
            output.recycle();
        }
    }

    private void drawPhotoCenterCrop(Canvas canvas, Bitmap bitmap, RectF destination, boolean mirror, float radius) {
        float sourceAspect = bitmap.getWidth() / (float) bitmap.getHeight();
        float destinationAspect = destination.width() / destination.height();
        Rect source;
        if (sourceAspect > destinationAspect) {
            int width = Math.round(bitmap.getHeight() * destinationAspect);
            int left = (bitmap.getWidth() - width) / 2;
            source = new Rect(left, 0, left + width, bitmap.getHeight());
        } else {
            int height = Math.round(bitmap.getWidth() / destinationAspect);
            int top = (bitmap.getHeight() - height) / 2;
            source = new Rect(0, top, bitmap.getWidth(), top + height);
        }
        int checkpoint = canvas.save();
        if (radius > 0) {
            Path clip = new Path();
            clip.addRoundRect(destination, radius, radius, Path.Direction.CW);
            canvas.clipPath(clip);
        }
        if (mirror) canvas.scale(-1f, 1f, destination.centerX(), destination.centerY());
        canvas.drawBitmap(bitmap, source, destination, new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG));
        canvas.restoreToCount(checkpoint);
    }

    private void drawPhotoWatermark(Canvas canvas, String value) {
        Paint background = new Paint(Paint.ANTI_ALIAS_FLAG);
        background.setColor(Color.argb(160, 0, 0, 0));
        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setColor(Color.WHITE);
        text.setTextSize(36);
        String[] lines = value.split("\\n");
        canvas.drawRoundRect(new RectF(36, 1740, 1044, 1888), 22, 22, background);
        float y = 1790;
        for (String raw : lines) {
            String line = raw;
            if (text.measureText(line) > 950) {
                int count = text.breakText(line, true, 920, null);
                line = line.substring(0, Math.max(0, count)) + "…";
            }
            canvas.drawText(line, 58, y, text);
            y += 52;
        }
    }

    private void setDualMode(boolean enabled) {
        if (enabled == dualMode) return;
        if (enabled) bindDualCamera(); else bindSingleCamera();
    }

    private void switchLens() {
        if (dualMode) {
            dualSwapped = !dualSwapped;
            if (dualCompositeRecorder != null && dualCompositeRecorder.isRunning()) {
                dualCompositeRecorder.setSwapCameras(dualSwapped);
            }
            statusView.setText(dualSwapped ? "正在切到前摄主画面…" : "正在切到后摄主画面…");
            bindDualCamera(true);
            return;
        }
        disableAllLights();
        frontFacing = !frontFacing;
        bindSingleCamera();
    }

    private void toggleTorch() {
        if (frontFacing || dualMode) {
            screenLightMode = (screenLightMode + 1) % 4;
            screenLightView.setMode(screenLightMode);
            android.view.WindowManager.LayoutParams attributes = getWindow().getAttributes();
            attributes.screenBrightness = screenLightMode == 0 ? originalScreenBrightness : 1f;
            getWindow().setAttributes(attributes);
            String[] names = {"前摄柔光已关闭", "前摄自然柔光", "前摄暖色柔光", "前摄冷色柔光"};
            statusView.setText(names[screenLightMode]);
            return;
        }
        if (primaryCamera == null || !primaryCamera.getCameraInfo().hasFlashUnit()) {
            showToast("当前镜头没有补光灯");
            return;
        }
        torchOn = !torchOn;
        primaryCamera.getCameraControl().enableTorch(torchOn);
        statusView.setText(torchOn ? "补光灯已打开" : "补光灯已关闭");
    }

    private void setZoom(float linearZoom) {
        if (primaryCamera == null) return;
        primaryCamera.getCameraControl().setLinearZoom(linearZoom);
        statusView.setText(String.format(Locale.CHINA, "变焦 %.0f%%", linearZoom * 100));
    }

    private void takePhoto() {
        if (dualMode) {
            requestDualPhoto();
            return;
        }
        if (imageCapture == null) {
            showToast("相机正在切换，请稍后再拍");
            return;
        }
        String name = "JingJie_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(new Date());
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/镜界创作");
        ImageCapture.OutputFileOptions options = new ImageCapture.OutputFileOptions.Builder(
            getContentResolver(), MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values).build();
        imageCapture.takePicture(options, ContextCompat.getMainExecutor(this), new ImageCapture.OnImageSavedCallback() {
            @Override public void onImageSaved(@NonNull ImageCapture.OutputFileResults output) {
                showToast("照片已保存到 Pictures/镜界创作");
            }
            @Override public void onError(@NonNull ImageCaptureException error) {
                showToast("拍照失败：" + error.getMessage());
            }
        });
    }

    private void toggleRecording() {
        if (preRecordManager != null) {
            if (preRecordManager.isCapturing()) {
                statusView.setText("正在拼接前 15 秒与本次录像…");
                preRecordManager.stopCapture();
            } else {
                preRecordManager.triggerCapture();
            }
            return;
        }
        if (dualMode) {
            float ratio = dualLayoutMode == 1 ? 0.5f : (dualLayoutMode == 2 ? -0.5f : 0f);
            toggleCompositeRecording(ratio, false, "双摄合成");
            return;
        }
        if (watermarkEnabled && singleCompositeAvailable) {
            toggleCompositeRecording(0f, frontFacing, "水印");
            return;
        }
        if (activeRecording != null) {
            activeRecording.stop();
            return;
        }
        if (videoCapture == null) {
            showToast("相机录像尚未就绪，请稍后重试");
            return;
        }
        String name = "JingJie_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(new Date());
        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, name);
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        values.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/镜界创作");
        MediaStoreOutputOptions options = new MediaStoreOutputOptions.Builder(
            getContentResolver(), MediaStore.Video.Media.EXTERNAL_CONTENT_URI).setContentValues(values).build();
        PendingRecording pending = videoCapture.getOutput().prepareRecording(this, options);
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            pending = pending.withAudioEnabled();
        }
        activeRecording = pending.start(ContextCompat.getMainExecutor(this), event -> {
            if (event instanceof VideoRecordEvent.Start) {
                recordingStartedAt = System.currentTimeMillis();
                recordButton.setText("停止");
                recordButton.setBackground(rounded(Color.rgb(238, 62, 74), 999, 4, Color.WHITE));
                statusView.setText("正在录像 · 无时长限制");
            } else if (event instanceof VideoRecordEvent.Finalize) {
                VideoRecordEvent.Finalize done = (VideoRecordEvent.Finalize) event;
                activeRecording = null;
                recordButton.setText("录像");
                recordButton.setBackground(rounded(Color.rgb(22, 139, 255), 999, 4, Color.WHITE));
                if (done.hasError()) showToast("录像失败：" + done.getError());
                else showToast("视频已保存到 Movies/镜界创作");
            }
        });
    }

    private void toggleCompositeRecording(float splitRatio, boolean mirrorPrimary, String recordingLabel) {
        if (dualCompositeRecorder != null && dualCompositeRecorder.isRunning()) {
            statusView.setText("正在完成双摄合成…");
            dualCompositeRecorder.stop();
            return;
        }
        RectF pipBounds = normalizedPipBounds();
        dualCompositeRecorder = new DualCompositeRecorder(this, splitRatio, mirrorPrimary, dualMode && dualSwapped,
            pipBounds, () -> watermarkEnabled ? currentWatermarkText : "", new DualCompositeRecorder.Listener() {
                @Override public void onStarted() {
                    recordingStartedAt = System.currentTimeMillis();
                    recordButton.setText("停止");
                    recordButton.setBackground(rounded(Color.rgb(238, 62, 74), 999, 4, Color.WHITE));
                    statusView.setText("正在录制" + recordingLabel + "视频 · 含麦克风声音");
                }

                @Override public void onFinished(android.net.Uri uri) {
                    dualCompositeRecorder = null;
                    resetRecordButton();
                    showToast(recordingLabel + "视频已保存到 Movies/镜界创作");
                }

                @Override public void onError(String message) {
                    dualCompositeRecorder = null;
                    resetRecordButton();
                    showToast(recordingLabel + "录像失败：" + message);
                    statusView.setText("相机预览仍可用，请重试录像");
                }
            });
        statusView.setText("正在启动" + recordingLabel + "编码器…");
        dualCompositeRecorder.start();
    }

    private void resetRecordButton() {
        recordButton.setText("录像");
        recordButton.setBackground(rounded(Color.rgb(22, 139, 255), 999, 4, Color.WHITE));
    }

    private void startPreRecordBuffer() {
        if (videoCapture == null || preRecordManager != null) return;
        preRecordManager = new PreRecordManager(this, videoCapture, new PreRecordManager.Listener() {
            @Override public void onBuffering(String message) {
                statusView.setText(message);
            }

            @Override public void onCaptureStarted() {
                recordingStartedAt = System.currentTimeMillis();
                recordButton.setText("停止");
                recordButton.setBackground(rounded(Color.rgb(238, 62, 74), 999, 4, Color.WHITE));
                statusView.setText("已保留按下前最多 15 秒 · 正在继续录像");
            }

            @Override public void onSaved(android.net.Uri uri) {
                if (preRecordManager != null) preRecordManager.dispose();
                preRecordManager = null;
                resetRecordButton();
                showToast("预录视频已保存（包含按下前最多 15 秒）");
                startPreRecordBuffer();
            }

            @Override public void onError(String message) {
                if (preRecordManager != null) preRecordManager.dispose();
                preRecordManager = null;
                resetRecordButton();
                statusView.setText(message);
            }
        });
        preRecordManager.startBuffering();
    }

    private void stopRecordingIfNeeded() {
        if (activeRecording != null) {
            activeRecording.stop();
            activeRecording = null;
        }
        if (dualCompositeRecorder != null) {
            dualCompositeRecorder.stop();
            dualCompositeRecorder = null;
        }
        if (preRecordManager != null) {
            preRecordManager.dispose();
            preRecordManager = null;
        }
    }

    private void editPrompt() {
        Dialog dialog = new Dialog(this);
        LinearLayout content = bottomSheet();
        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout headingText = new LinearLayout(this);
        headingText.setOrientation(LinearLayout.VERTICAL);
        headingText.addView(label("提词设置", 23, Color.WHITE));
        headingText.addView(label("编辑文稿，然后选择滚动或语音跟读", 13, Color.rgb(145, 150, 158)));
        heading.addView(headingText, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView close = sheetTextButton("关闭", Color.rgb(50, 50, 54));
        close.setOnClickListener(v -> dialog.dismiss());
        heading.addView(close, new LinearLayout.LayoutParams(dp(62), dp(38)));
        content.addView(heading);

        EditText editor = new EditText(this);
        editor.setHint("输入或粘贴口播文稿…");
        editor.setHintTextColor(Color.rgb(105, 108, 115));
        editor.setTextColor(Color.WHITE);
        editor.setTextSize(18);
        editor.setGravity(Gravity.TOP);
        editor.setPadding(dp(15), dp(13), dp(15), dp(13));
        editor.setBackground(rounded(Color.rgb(25, 25, 28), 16, 1, Color.rgb(57, 57, 62)));
        editor.setText(promptText.getText());
        LinearLayout.LayoutParams editorParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(170));
        editorParams.topMargin = dp(16);
        content.addView(editor, editorParams);

        LinearLayout speedHeading = new LinearLayout(this);
        speedHeading.setGravity(Gravity.CENTER_VERTICAL);
        TextView speedTitle = label("滚动速度", 15, Color.WHITE);
        speedHeading.addView(speedTitle, new LinearLayout.LayoutParams(0, dp(42), 1f));
        TextView speedValue = label(promptSpeedName(Math.max(0, promptStep - 1)), 13, Color.rgb(79, 170, 255));
        speedValue.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        speedHeading.addView(speedValue, new LinearLayout.LayoutParams(dp(90), dp(42)));
        content.addView(speedHeading);
        SeekBar speed = new SeekBar(this);
        speed.setMax(8);
        speed.setProgress(Math.max(0, promptStep - 1));
        speed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                speedValue.setText(promptSpeedName(progress));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        content.addView(speed, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(38)));

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER);
        TextView hide = sheetTextButton("隐藏提词", Color.rgb(48, 48, 52));
        TextView voice = sheetTextButton("语音跟读", Color.rgb(46, 58, 91));
        TextView scroll = sheetTextButton("开始滚动", Color.rgb(41, 139, 255));
        TextView[] actionViews = {hide, voice, scroll};
        for (int i = 0; i < actionViews.length; i++) {
            LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(0, dp(52), 1f);
            actionParams.setMargins(i == 0 ? 0 : dp(4), dp(14), i == 2 ? 0 : dp(4), 0);
            actions.addView(actionViews[i], actionParams);
        }
        content.addView(actions);
        hide.setOnClickListener(v -> {
            promptRunning = false;
            stopVoiceFollow();
            promptPanel.setVisibility(View.GONE);
            dialog.dismiss();
        });
        voice.setOnClickListener(v -> {
            applyPromptEditorText(editor);
            promptPanel.setVisibility(View.VISIBLE);
            promptPanel.scrollTo(0, 0);
            dialog.dismiss();
            startVoiceFollow();
        });
        scroll.setOnClickListener(v -> {
            stopVoiceFollow();
            applyPromptEditorText(editor);
            promptStep = speed.getProgress() + 1;
            promptPanel.setVisibility(View.VISIBLE);
            promptPanel.scrollTo(0, 0);
            promptRunning = true;
            handler.removeCallbacks(promptTick);
            handler.post(promptTick);
            dialog.dismiss();
        });
        showBottomDialog(dialog, content);
    }

    private void applyPromptEditorText(EditText editor) {
        String value = editor.getText().toString().trim();
        if (!value.isEmpty()) promptText.setText(value);
    }

    private String promptSpeedName(int progress) {
        if (progress <= 2) return "慢速";
        if (progress <= 5) return "适中";
        return "快速";
    }

    private void startVoiceFollow() {
        boolean available = SpeechRecognizer.isRecognitionAvailable(this);
        if (!available) {
            showToast("系统没有可用的语音识别服务");
            return;
        }
        stopVoiceFollow();
        promptRunning = false;
        voiceFollowing = true;
        voiceCommittedChars = 0;
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) {
                statusView.setText("语音跟读中 · 按文稿匹配进度滚动");
            }
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float rmsdB) {}
            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEndOfSpeech() { speechListening = false; }
            @Override public void onError(int error) {
                speechListening = false;
                if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    voiceFollowing = false;
                    statusView.setText("系统语音服务无法使用麦克风，请检查录音权限");
                    return;
                }
                scheduleVoiceRestart(error == SpeechRecognizer.ERROR_CLIENT ? 1200 : 700);
            }
            @Override public void onResults(Bundle results) {
                speechListening = false;
                updateVoiceProgress(results, true);
                scheduleVoiceRestart(350);
            }
            @Override public void onPartialResults(Bundle partialResults) { updateVoiceProgress(partialResults, false); }
            @Override public void onEvent(int eventType, Bundle params) {}
        });
        listenForPromptSpeech();
    }

    private void listenForPromptSpeech() {
        if (!voiceFollowing || speechRecognizer == null || speechListening) return;
        voiceRestartScheduled = false;
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN");
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        try {
            speechListening = true;
            speechRecognizer.startListening(intent);
        } catch (RuntimeException ignored) {
            speechListening = false;
            scheduleVoiceRestart(1200);
        }
    }

    private void scheduleVoiceRestart(long delayMs) {
        if (!voiceFollowing || voiceRestartScheduled) return;
        voiceRestartScheduled = true;
        handler.removeCallbacks(voiceRestartTask);
        handler.postDelayed(voiceRestartTask, delayMs);
    }

    private void updateVoiceProgress(Bundle results, boolean commit) {
        ArrayList<String> recognized = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (recognized == null || recognized.isEmpty()) return;
        String script = normalizeSpeechText(promptText.getText().toString());
        String heard = normalizeSpeechText(recognized.get(0));
        if (heard.isEmpty()) return;
        int total = Math.max(1, script.length());
        int progressChars = matchSpeechProgress(script, heard, voiceCommittedChars);
        if (progressChars <= voiceCommittedChars) {
            statusView.setText("语音跟读中 · 正在等待匹配文稿");
            return;
        }
        if (commit) voiceCommittedChars = progressChars;
        int maxScroll = Math.max(0, promptText.getHeight() - promptPanel.getHeight() + dp(48));
        int target = Math.round(maxScroll * (progressChars / (float) total));
        promptPanel.smoothScrollTo(0, target);
        statusView.setText("语音跟读 · 已匹配 " + Math.round(progressChars * 100f / total) + "%");
    }

    private String normalizeSpeechText(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.CHINA).replaceAll("[\\p{P}\\p{Z}\\s]+", "");
    }

    private int matchSpeechProgress(String script, String heard, int committed) {
        if (script.isEmpty() || heard.isEmpty()) return committed;
        int searchStart = Math.max(0, committed - 8);
        int searchEnd = Math.min(script.length(), committed + 220);
        String window = script.substring(searchStart, searchEnd);
        int exact = window.indexOf(heard);
        if (exact >= 0) return Math.max(committed, searchStart + exact + heard.length());

        int minimum = Math.min(3, heard.length());
        for (int length = heard.length() - 1; length >= minimum; length--) {
            for (int start = 0; start + length <= heard.length(); start++) {
                String fragment = heard.substring(start, start + length);
                int index = window.indexOf(fragment);
                if (index >= 0) return Math.max(committed, searchStart + index + fragment.length());
            }
        }
        return committed;
    }

    private void stopVoiceFollow() {
        voiceFollowing = false;
        speechListening = false;
        voiceRestartScheduled = false;
        handler.removeCallbacks(voiceRestartTask);
        if (speechRecognizer != null) {
            try { speechRecognizer.cancel(); } catch (RuntimeException ignored) {}
            speechRecognizer.destroy();
            speechRecognizer = null;
        }
    }

    private void enablePromptPreset(String script) {
        promptText.setText(script);
        promptPanel.setVisibility(View.VISIBLE);
        promptPanel.scrollTo(0, 0);
        promptRunning = true;
        handler.removeCallbacks(promptTick);
        handler.post(promptTick);
        statusView.setText(sceneTitle + " · 提词已准备");
    }

    private void toggleWatermark() {
        watermarkEnabled = !watermarkEnabled;
        watermarkView.setVisibility(watermarkEnabled ? View.VISIBLE : View.GONE);
        if (watermarkEnabled) updateLastLocation();
        statusView.setText(watermarkEnabled ? "时间 / 位置 / 方位水印已显示" : "水印已隐藏");
    }

    private void updateLastLocation() {
        boolean fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (!fine && !coarse) return;
        try {
            LocationManager manager = (LocationManager) getSystemService(LOCATION_SERVICE);
            Location gps = manager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            Location network = manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            lastLocation = chooseBetterLocation(gps, network);
            if (lastLocation != null) {
                resolvePlaceName(lastLocation);
            }
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    requestFreshLocation(manager, LocationManager.NETWORK_PROVIDER);
                }
                if (fine && manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    requestFreshLocation(manager, LocationManager.GPS_PROVIDER);
                }
            }
        } catch (RuntimeException ignored) {
            lastLocation = null;
            placeName = "位置暂不可用";
        }
    }

    @android.annotation.TargetApi(30)
    private void requestFreshLocation(LocationManager manager, String provider) {
        boolean fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (!fine && !coarse) return;
        try {
            manager.getCurrentLocation(provider, new CancellationSignal(), ContextCompat.getMainExecutor(this), location -> {
                if (location == null) return;
                Location better = chooseBetterLocation(lastLocation, location);
                if (better == location) {
                    lastLocation = location;
                    resolvePlaceName(location);
                }
            });
        } catch (SecurityException ignored) {
            placeName = "位置权限不可用";
        }
    }

    private Location chooseBetterLocation(Location first, Location second) {
        if (first == null) return second;
        if (second == null) return first;
        long timeDifference = second.getTime() - first.getTime();
        if (timeDifference > 120_000) return second;
        if (timeDifference < -120_000) return first;
        if (first.hasAccuracy() && second.hasAccuracy()) {
            if (second.getAccuracy() + 15 < first.getAccuracy()) return second;
            if (first.getAccuracy() + 15 < second.getAccuracy()) return first;
        } else if (second.hasAccuracy()) {
            return second;
        } else if (first.hasAccuracy()) {
            return first;
        }
        return second.getTime() > first.getTime() ? second : first;
    }

    private void resolvePlaceName(Location location) {
        placeName = "正在解析地名";
        geocodeExecutor.execute(() -> {
            String resolved = "位置已记录";
            try {
                Geocoder geocoder = new Geocoder(this, Locale.CHINA);
                List<Address> addresses = geocoder.getFromLocation(location.getLatitude(), location.getLongitude(), 1);
                if (addresses != null && !addresses.isEmpty()) resolved = readablePlace(addresses.get(0));
            } catch (Exception ignored) {
                resolved = "位置已记录";
            }
            if (location.hasAccuracy() && location.getAccuracy() > 80) {
                resolved += "（约 " + Math.round(location.getAccuracy()) + " 米）";
            }
            String finalName = resolved;
            handler.post(() -> {
                placeName = finalName;
                updateWatermark();
            });
        });
    }

    private String readablePlace(Address address) {
        Set<String> parts = new LinkedHashSet<>();
        addPlacePart(parts, address.getAdminArea());
        addPlacePart(parts, address.getLocality());
        addPlacePart(parts, address.getSubLocality());
        if (parts.size() < 3) addPlacePart(parts, address.getThoroughfare());
        if (parts.size() < 3) addPlacePart(parts, address.getFeatureName());
        if (parts.isEmpty()) return "位置已记录";
        StringBuilder result = new StringBuilder();
        int count = 0;
        for (String part : parts) {
            if (result.length() > 0) result.append(" · ");
            result.append(part);
            if (++count >= 3 || result.length() >= 18) break;
        }
        return result.toString();
    }

    private void addPlacePart(Set<String> parts, String value) {
        if (value != null && !value.trim().isEmpty() && !value.matches("[-+]?\\d+(\\.\\d+)?")) {
            parts.add(value.trim());
        }
    }

    private void disableAllLights() {
        if (primaryCamera != null && torchOn) primaryCamera.getCameraControl().enableTorch(false);
        torchOn = false;
        screenLightMode = 0;
        if (screenLightView != null) screenLightView.setMode(0);
        android.view.WindowManager.LayoutParams attributes = getWindow().getAttributes();
        attributes.screenBrightness = originalScreenBrightness;
        getWindow().setAttributes(attributes);
    }

    private void updateWatermark() {
        if (watermarkView == null || !watermarkEnabled) return;
        String direction = headingToDirection(headingDegrees);
        String location = lastLocation == null ? "正在获取地名" : placeName;
        currentWatermarkText = clockFormat.format(new Date()) + "\n" + location + "  ·  " + direction + " " + Math.round(headingDegrees) + "°";
        watermarkView.setText(currentWatermarkText);
    }

    private String headingToDirection(float degrees) {
        String[] names = {"北", "东北", "东", "东南", "南", "西南", "西", "西北"};
        return names[Math.round(degrees / 45f) & 7];
    }

    private void openGallery() {
        android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW);
        intent.setDataAndType(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video/*");
        try { startActivity(intent); } catch (Exception error) { showToast("没有可用的相册应用"); }
    }

    @Override protected void onResume() {
        super.onResume();
        Sensor rotation = sensorManager == null ? null : sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        if (rotation != null) sensorManager.registerListener(this, rotation, SensorManager.SENSOR_DELAY_UI);
    }

    @Override protected void onPause() {
        super.onPause();
        if (sensorManager != null) sensorManager.unregisterListener(this);
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        stopRecordingIfNeeded();
        stopVoiceFollow();
        disableAllLights();
        geocodeExecutor.shutdownNow();
        analysisExecutor.shutdownNow();
        synchronized (dualPhotoLock) {
            dualPhotoPending = false;
            if (dualPhotoBack != null) dualPhotoBack.recycle();
            if (dualPhotoFront != null) dualPhotoFront.recycle();
            dualPhotoBack = null;
            dualPhotoFront = null;
        }
        super.onDestroy();
    }

    @Override public void onSensorChanged(SensorEvent event) {
        float[] matrix = new float[9];
        float[] orientation = new float[3];
        SensorManager.getRotationMatrixFromVector(matrix, event.values);
        SensorManager.getOrientation(matrix, orientation);
        headingDegrees = (float) Math.toDegrees(orientation[0]);
        if (headingDegrees < 0) headingDegrees += 360f;
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    private Button toolButton(String text, View.OnClickListener listener) {
        Button button = iconButton(text);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(58), dp(48));
        params.bottomMargin = dp(8);
        button.setLayoutParams(params);
        return button;
    }

    private Button iconButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(11);
        button.setPadding(dp(4), 0, dp(4), 0);
        button.setAllCaps(false);
        button.setBackground(rounded(Color.argb(145, 0, 0, 0), 18, 0, 0));
        return button;
    }

    private Button zoomButton(String text, float zoom) {
        Button button = iconButton(text);
        button.setOnClickListener(v -> setZoom(zoom));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(62), dp(34));
        params.leftMargin = dp(3);
        params.rightMargin = dp(3);
        button.setLayoutParams(params);
        return button;
    }

    private Button modeButton(String text, View.OnClickListener listener) {
        Button button = iconButton(text);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(38));
        params.leftMargin = dp(3);
        params.rightMargin = dp(3);
        button.setLayoutParams(params);
        return button;
    }

    private Button circleButton(String text, int size, int color, int textColor) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(12);
        button.setTextColor(textColor);
        button.setAllCaps(false);
        button.setPadding(0, 0, 0, 0);
        button.setBackground(rounded(color, 999, 4, Color.argb(230, 255, 255, 255)));
        button.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        return button;
    }

    private TextView chip(String text) {
        TextView view = label(text, 14, Color.WHITE);
        view.setGravity(Gravity.CENTER);
        view.setBackground(rounded(Color.argb(140, 0, 0, 0), 18, 0, 0));
        view.setPadding(dp(12), 0, dp(12), 0);
        return view;
    }

    private TextView label(String text, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(sp);
        view.setTextColor(color);
        return view;
    }

    private LinearLayout bottomSheet() {
        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(20), dp(20), dp(20), dp(24));
        sheet.setBackground(rounded(Color.rgb(18, 18, 20), 26, 1, Color.rgb(55, 55, 60)));
        sheet.setElevation(dp(16));
        return sheet;
    }

    private TextView sheetTextButton(String text, int color) {
        TextView button = label(text, 14, Color.WHITE);
        button.setGravity(Gravity.CENTER);
        button.setTypeface(null, android.graphics.Typeface.BOLD);
        button.setBackground(rounded(color, 14, 0, 0));
        button.setClickable(true);
        button.setFocusable(true);
        return button;
    }

    private void showBottomDialog(Dialog dialog, View content) {
        dialog.setContentView(content);
        dialog.show();
        Window window = dialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        WindowManager.LayoutParams attributes = window.getAttributes();
        attributes.dimAmount = 0.58f;
        window.setAttributes(attributes);
        window.setGravity(Gravity.BOTTOM);
        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        window.getDecorView().setPadding(dp(10), 0, dp(10), dp(10));
    }

    private FrameLayout.LayoutParams matchFrame() {
        return new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
    }

    private GradientDrawable rounded(int color, int radiusDp, int strokeDp, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) drawable.setStroke(dp(strokeDp), strokeColor);
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void showToast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }
}
