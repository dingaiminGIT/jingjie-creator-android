package com.baiyalab.cocamera;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Bundle;
import android.os.Build;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.Locale;

public final class PromptOverlayService extends Service {
    public static final String EXTRA_PROMPT = "prompt";
    public static final String EXTRA_VOICE_FOLLOW = "voice_follow";
    public static final String EXTRA_SCROLL_SPEED = "scroll_speed";
    private static final String CHANNEL_ID = "floating_creator";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager windowManager;
    private View overlay;
    private ScrollView promptScroll;
    private TextView promptText;
    private TextView modeStatus;
    private SpeechRecognizer speechRecognizer;
    private boolean running = true;
    private boolean voiceFollowing;
    private boolean speechListening;
    private boolean restartScheduled;
    private int voiceCommittedChars;
    private int scrollSpeed = 2;
    private int dragStartX;
    private int dragStartY;
    private float touchStartX;
    private float touchStartY;

    private final Runnable scrollTick = new Runnable() {
        @Override public void run() {
            if (running && !voiceFollowing && promptScroll != null) {
                int max = Math.max(0, promptText.getHeight() - promptScroll.getHeight() + dp(32));
                int next = promptScroll.getScrollY() + scrollSpeed;
                promptScroll.scrollTo(0, next >= max ? 0 : next);
            }
            handler.postDelayed(this, 50);
        }
    };

    private final Runnable voiceRestart = new Runnable() {
        @Override public void run() {
            restartScheduled = false;
            startListening();
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        stopVoiceFollow();
        removeOverlay();
        handler.removeCallbacksAndMessages(null);
        running = true;
        String suppliedPrompt = intent.getStringExtra(EXTRA_PROMPT);
        if (suppliedPrompt != null) {
            getSharedPreferences("floating", MODE_PRIVATE).edit()
                .putString("prompt", suppliedPrompt)
                .putBoolean("voice_follow", intent.getBooleanExtra(EXTRA_VOICE_FOLLOW, false))
                .putInt("scroll_speed", intent.getIntExtra(EXTRA_SCROLL_SPEED, 2))
                .apply();
        }
        Notification notification = createNotification();
        voiceFollowing = getSharedPreferences("floating", MODE_PRIVATE).getBoolean("voice_follow", false);
        scrollSpeed = getSharedPreferences("floating", MODE_PRIVATE).getInt("scroll_speed", 2);
        if (Build.VERSION.SDK_INT >= 29) {
            int type = voiceFollowing ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE : 0;
            if (Build.VERSION.SDK_INT >= 34) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
            startForeground(1001, notification, type);
        } else {
            startForeground(1001, notification);
        }
        showOverlay();
        if (voiceFollowing) startVoiceFollow();
        return START_NOT_STICKY;
    }

    private void showOverlay() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(10), dp(8), dp(10), dp(10));
        panel.setBackground(rounded(Color.argb(225, 18, 18, 20), 20, 1, Color.argb(180, 130, 130, 140)));

        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        TextView handle = text("拖动镜界创作", 13, Color.LTGRAY);
        controls.addView(handle, new LinearLayout.LayoutParams(0, dp(40), 1f));
        Button play = button("暂停");
        play.setOnClickListener(v -> {
            running = !running;
            play.setText(running ? "暂停" : "播放");
            if (voiceFollowing) {
                if (running) startListening();
                else cancelListening();
            }
        });
        controls.addView(play, new LinearLayout.LayoutParams(dp(66), dp(40)));
        Button mode = button(voiceFollowing ? "跟读" : "匀速");
        mode.setOnClickListener(v -> {
            voiceFollowing = !voiceFollowing;
            mode.setText(voiceFollowing ? "跟读" : "匀速");
            voiceCommittedChars = 0;
            promptScroll.scrollTo(0, 0);
            if (voiceFollowing) startVoiceFollow(); else stopVoiceFollow();
            updateModeStatus();
        });
        controls.addView(mode, new LinearLayout.LayoutParams(dp(58), dp(40)));
        Button close = button("×");
        close.setTextSize(24);
        close.setOnClickListener(v -> stopSelf());
        controls.addView(close, new LinearLayout.LayoutParams(dp(48), dp(40)));
        panel.addView(controls);

        promptText = text(getSharedPreferences("floating", MODE_PRIVATE)
            .getString("prompt", "看着镜头，自然表达。"), 20, Color.WHITE);
        promptText.setGravity(Gravity.CENTER);
        promptText.setLineSpacing(dp(7), 1f);
        promptText.setPadding(dp(8), dp(54), dp(8), dp(110));
        promptScroll = new ScrollView(this);
        promptScroll.setVerticalScrollBarEnabled(false);
        promptScroll.addView(promptText, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        panel.addView(promptScroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        modeStatus = text("", 11, Color.rgb(170, 173, 181));
        modeStatus.setGravity(Gravity.CENTER);
        panel.addView(modeStatus, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(24)));
        updateModeStatus();

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
            dp(330), dp(230), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = dp(28);
        params.y = dp(120);
        overlay = panel;
        handle.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                dragStartX = params.x;
                dragStartY = params.y;
                touchStartX = event.getRawX();
                touchStartY = event.getRawY();
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_MOVE) {
                params.x = dragStartX + Math.round(event.getRawX() - touchStartX);
                params.y = dragStartY + Math.round(event.getRawY() - touchStartY);
                windowManager.updateViewLayout(overlay, params);
                return true;
            }
            return false;
        });
        windowManager.addView(overlay, params);
        handler.post(scrollTick);
    }

    private void updateModeStatus() {
        if (modeStatus == null) return;
        if (!voiceFollowing) modeStatus.setText("匀速滚动 · 速度 " + scrollSpeed);
        else if (!SpeechRecognizer.isRecognitionAvailable(this)) modeStatus.setText("系统语音服务不可用");
        else modeStatus.setText("语音跟读 · 等待文稿匹配");
    }

    private void startVoiceFollow() {
        stopVoiceFollow();
        voiceFollowing = true;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
            || !SpeechRecognizer.isRecognitionAvailable(this)) {
            voiceFollowing = false;
            updateModeStatus();
            return;
        }
        if (Build.VERSION.SDK_INT >= 29) {
            int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            if (Build.VERSION.SDK_INT >= 34) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
            try {
                startForeground(1001, createNotification(), type);
            } catch (RuntimeException error) {
                voiceFollowing = false;
                if (modeStatus != null) modeStatus.setText("请回到镜界创作重新开启语音跟读");
                return;
            }
        }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) {
                speechListening = true;
                if (modeStatus != null) modeStatus.setText("语音跟读 · 正在听");
            }
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float rmsdB) {}
            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEndOfSpeech() { speechListening = false; }
            @Override public void onError(int error) {
                speechListening = false;
                if (modeStatus != null) modeStatus.setText("语音跟读 · 等待继续");
                scheduleRestart(error == SpeechRecognizer.ERROR_CLIENT ? 1200 : 700);
            }
            @Override public void onResults(Bundle results) {
                speechListening = false;
                updateVoiceProgress(results, true);
                scheduleRestart(350);
            }
            @Override public void onPartialResults(Bundle partialResults) { updateVoiceProgress(partialResults, false); }
            @Override public void onEvent(int eventType, Bundle params) {}
        });
        startListening();
    }

    private void startListening() {
        if (!voiceFollowing || !running || speechRecognizer == null || speechListening) return;
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.CHINA.toLanguageTag());
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        try {
            speechListening = true;
            speechRecognizer.startListening(intent);
        } catch (RuntimeException ignored) {
            speechListening = false;
            scheduleRestart(1200);
        }
    }

    private void scheduleRestart(long delay) {
        if (!voiceFollowing || restartScheduled) return;
        restartScheduled = true;
        handler.postDelayed(voiceRestart, delay);
    }

    private void updateVoiceProgress(Bundle results, boolean commit) {
        if (!running || promptText == null || promptScroll == null) return;
        ArrayList<String> recognized = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (recognized == null || recognized.isEmpty()) return;
        String script = normalize(promptText.getText().toString());
        String heard = normalize(recognized.get(0));
        int progress = matchProgress(script, heard, voiceCommittedChars);
        if (progress <= voiceCommittedChars) return;
        if (commit) voiceCommittedChars = progress;
        int maxScroll = Math.max(0, promptText.getHeight() - promptScroll.getHeight() + dp(32));
        promptScroll.smoothScrollTo(0, Math.round(maxScroll * (progress / (float) Math.max(1, script.length()))));
        if (modeStatus != null) modeStatus.setText("语音跟读 · 已匹配 " + Math.round(progress * 100f / Math.max(1, script.length())) + "%");
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.CHINA).replaceAll("[\\p{P}\\p{Z}\\s]+", "");
    }

    private int matchProgress(String script, String heard, int committed) {
        if (script.isEmpty() || heard.isEmpty()) return committed;
        int from = Math.max(0, committed - 8);
        int to = Math.min(script.length(), committed + 220);
        String window = script.substring(from, to);
        int exact = window.indexOf(heard);
        if (exact >= 0) return Math.max(committed, from + exact + heard.length());
        for (int length = heard.length() - 1; length >= Math.min(3, heard.length()); length--) {
            for (int start = 0; start + length <= heard.length(); start++) {
                int index = window.indexOf(heard.substring(start, start + length));
                if (index >= 0) return Math.max(committed, from + index + length);
            }
        }
        return committed;
    }

    private void cancelListening() {
        speechListening = false;
        handler.removeCallbacks(voiceRestart);
        restartScheduled = false;
        if (speechRecognizer != null) {
            try { speechRecognizer.cancel(); } catch (RuntimeException ignored) {}
        }
    }

    private void stopVoiceFollow() {
        cancelListening();
        if (speechRecognizer != null) speechRecognizer.destroy();
        speechRecognizer = null;
    }

    private void createChannel() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "悬浮创作", NotificationManager.IMPORTANCE_LOW);
        manager.createNotificationChannel(channel);
    }

    private Notification createNotification() {
        Intent reopen = new Intent(this, FloatingHubActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 1, reopen,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("镜界创作悬浮提词运行中")
            .setContentText("点按返回设置，悬浮窗内可直接关闭")
            .setOngoing(true)
            .setContentIntent(pending)
            .build();
    }

    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        stopVoiceFollow();
        removeOverlay();
        super.onDestroy();
    }

    private void removeOverlay() {
        if (overlay != null && windowManager != null) {
            try { windowManager.removeView(overlay); } catch (RuntimeException ignored) {}
        }
        overlay = null;
        promptScroll = null;
        promptText = null;
        modeStatus = null;
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    private Button button(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextColor(Color.WHITE);
        button.setTextSize(12);
        button.setAllCaps(false);
        button.setPadding(0, 0, 0, 0);
        button.setBackground(rounded(Color.argb(180, 55, 55, 60), 14, 0, 0));
        return button;
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
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
}
