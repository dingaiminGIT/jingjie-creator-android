package com.baiyalab.cocamera;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Build;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Floating reference player used while the user records in another app. */
public final class FloatingMediaOverlayService extends Service {
    public static final String ACTION_VIDEO = "com.baiyalab.cocamera.FLOAT_VIDEO";
    public static final String ACTION_GALLERY = "com.baiyalab.cocamera.FLOAT_GALLERY";
    public static final String EXTRA_VIDEO_URI = "video_uri";
    public static final String EXTRA_IMAGE_URIS = "image_uris";

    private static final String CHANNEL_ID = "floating_reference";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService imageExecutor = Executors.newSingleThreadExecutor();
    private final Set<Integer> completedImages = new HashSet<>();
    private WindowManager windowManager;
    private WindowManager.LayoutParams windowParams;
    private View overlay;
    private TextureView videoView;
    private MediaPlayer mediaPlayer;
    private Surface videoSurface;
    private SeekBar videoSeek;
    private TextView timeLabel;
    private ImageView imageView;
    private TextView imageCounter;
    private Button completeButton;
    private ArrayList<String> imageUris = new ArrayList<>();
    private int imageIndex;
    private int sizeMode;
    private int dragStartX;
    private int dragStartY;
    private float touchStartX;
    private float touchStartY;
    private float playbackSpeed = 1f;
    private boolean mirrored;
    private boolean repeat = true;
    private boolean muted;
    private boolean videoPrepared;
    private int imageGeneration;
    private Bitmap displayedBitmap;

    private final Runnable progressTick = new Runnable() {
        @Override public void run() {
            if (videoView != null && videoSeek != null) {
                int duration = videoDuration();
                int position = videoPosition();
                videoSeek.setMax(Math.max(1, duration));
                videoSeek.setProgress(Math.min(position, Math.max(1, duration)));
                if (timeLabel != null) timeLabel.setText(formatTime(position) + " / " + formatTime(duration));
            }
            handler.postDelayed(this, 300);
        }
    };

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) return START_NOT_STICKY;
        if (overlay != null) removeOverlay();
        createChannel();
        boolean video = ACTION_VIDEO.equals(intent.getAction());
        Notification notification = notification(video ? "视频跟拍" : "图集跟拍");
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1101, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1101, notification);
        }
        try {
            if (video) {
                String uri = intent.getStringExtra(EXTRA_VIDEO_URI);
                if (uri == null) throw new IllegalArgumentException("未选择参考视频");
                showVideoOverlay(Uri.parse(uri));
            } else if (ACTION_GALLERY.equals(intent.getAction())) {
                ArrayList<String> values = intent.getStringArrayListExtra(EXTRA_IMAGE_URIS);
                if (values == null) {
                    String[] array = intent.getStringArrayExtra(EXTRA_IMAGE_URIS);
                    if (array != null) {
                        values = new ArrayList<>();
                        java.util.Collections.addAll(values, array);
                    }
                }
                if (values == null || values.isEmpty()) throw new IllegalArgumentException("未选择参考图片");
                imageUris = values;
                imageIndex = 0;
                showGalleryOverlay();
            }
        } catch (RuntimeException error) {
            Log.e("FloatingMediaOverlay", "Unable to create overlay", error);
            Toast.makeText(this, "悬浮跟拍启动失败：" + error.getMessage(), Toast.LENGTH_LONG).show();
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void showVideoOverlay(Uri uri) {
        LinearLayout panel = basePanel("视频跟拍", "拖动");
        videoView = new TextureView(this);
        // TextureView rejects background drawables on several vendor builds.
        // Its surface is opaque, while the panel behind it supplies the black
        // placeholder shown before the first video frame arrives.
        videoView.setOpaque(true);
        videoView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
                prepareVideo(uri, texture);
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {}
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
                releaseVideo();
                return true;
            }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture texture) {}
        });
        panel.addView(videoView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        videoSeek = new SeekBar(this);
        videoSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) seekVideo(progress);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        panel.addView(videoSeek, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(34)));

        LinearLayout transport = controlRow();
        Button rewind = button("−5s");
        rewind.setOnClickListener(v -> seekVideo(Math.max(0, videoPosition() - 5000)));
        transport.addView(rewind, weightedButton());
        Button play = button("暂停");
        play.setOnClickListener(v -> {
            if (isVideoPlaying()) {
                mediaPlayer.pause();
                play.setText("播放");
            } else if (videoPrepared && mediaPlayer != null) {
                mediaPlayer.start();
                play.setText("暂停");
            }
        });
        transport.addView(play, weightedButton());
        Button forward = button("+5s");
        forward.setOnClickListener(v -> seekVideo(Math.min(videoDuration(), videoPosition() + 5000)));
        transport.addView(forward, weightedButton());
        panel.addView(transport, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42)));

        LinearLayout options = controlRow();
        Button speed = button("1.0×");
        speed.setOnClickListener(v -> {
            playbackSpeed = playbackSpeed == 1f ? 0.5f : playbackSpeed == 0.5f ? 0.75f : 1f;
            applyPlaybackSpeed();
            speed.setText(String.format(Locale.CHINA, "%.2g×", playbackSpeed));
        });
        options.addView(speed, weightedButton());
        Button mirror = button("镜像");
        mirror.setOnClickListener(v -> {
            mirrored = !mirrored;
            videoView.setScaleX(mirrored ? -1f : 1f);
            mirror.setText(mirrored ? "已镜像" : "镜像");
        });
        options.addView(mirror, weightedButton());
        Button mute = button("声音开");
        mute.setOnClickListener(v -> {
            muted = !muted;
            if (mediaPlayer != null) mediaPlayer.setVolume(muted ? 0f : 1f, muted ? 0f : 1f);
            mute.setText(muted ? "已静音" : "声音开");
        });
        options.addView(mute, weightedButton());
        Button loop = button("循环开");
        loop.setOnClickListener(v -> {
            repeat = !repeat;
            if (mediaPlayer != null) mediaPlayer.setLooping(repeat);
            loop.setText(repeat ? "循环开" : "循环关");
        });
        options.addView(loop, weightedButton());
        panel.addView(options, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42)));

        timeLabel = text("00:00 / 00:00", 11, Color.rgb(175, 178, 184));
        timeLabel.setGravity(Gravity.CENTER);
        panel.addView(timeLabel, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(24)));

        attachOverlay(panel, dp(350), dp(360));
        handler.post(progressTick);
    }

    private void prepareVideo(Uri uri, SurfaceTexture texture) {
        releaseVideo();
        try {
            videoSurface = new Surface(texture);
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(this, uri);
            mediaPlayer.setSurface(videoSurface);
            mediaPlayer.setLooping(repeat);
            mediaPlayer.setVolume(muted ? 0f : 1f, muted ? 0f : 1f);
            mediaPlayer.setOnPreparedListener(player -> {
                videoPrepared = true;
                applyPlaybackSpeed();
                player.start();
            });
            mediaPlayer.setOnErrorListener((player, what, extra) -> {
                videoPrepared = false;
                if (timeLabel != null) timeLabel.setText("参考视频无法播放");
                return true;
            });
            mediaPlayer.prepareAsync();
        } catch (Exception error) {
            releaseVideo();
            if (timeLabel != null) timeLabel.setText("参考视频无法播放");
        }
    }

    private int videoDuration() {
        if (!videoPrepared || mediaPlayer == null) return 0;
        try { return Math.max(0, mediaPlayer.getDuration()); } catch (RuntimeException ignored) { return 0; }
    }

    private int videoPosition() {
        if (!videoPrepared || mediaPlayer == null) return 0;
        try { return Math.max(0, mediaPlayer.getCurrentPosition()); } catch (RuntimeException ignored) { return 0; }
    }

    private boolean isVideoPlaying() {
        if (!videoPrepared || mediaPlayer == null) return false;
        try { return mediaPlayer.isPlaying(); } catch (RuntimeException ignored) { return false; }
    }

    private void seekVideo(int position) {
        if (!videoPrepared || mediaPlayer == null) return;
        try { mediaPlayer.seekTo(Math.max(0, Math.min(videoDuration(), position))); } catch (RuntimeException ignored) {}
    }

    private void releaseVideo() {
        videoPrepared = false;
        if (mediaPlayer != null) {
            try { mediaPlayer.stop(); } catch (RuntimeException ignored) {}
            mediaPlayer.release();
        }
        mediaPlayer = null;
        if (videoSurface != null) videoSurface.release();
        videoSurface = null;
    }

    private void showGalleryOverlay() {
        LinearLayout panel = basePanel("图集跟拍", "拖动");
        imageView = new ImageView(this);
        imageView.setBackgroundColor(Color.BLACK);
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        panel.addView(imageView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        imageCounter = text("", 12, Color.rgb(190, 192, 198));
        imageCounter.setGravity(Gravity.CENTER);
        panel.addView(imageCounter, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(30)));

        LinearLayout navigation = controlRow();
        Button previous = button("上一张");
        previous.setOnClickListener(v -> {
            imageIndex = (imageIndex - 1 + imageUris.size()) % imageUris.size();
            showCurrentImage();
        });
        navigation.addView(previous, weightedButton());
        completeButton = button("标记完成");
        completeButton.setOnClickListener(v -> {
            if (completedImages.contains(imageIndex)) completedImages.remove(imageIndex);
            else completedImages.add(imageIndex);
            showCurrentImage();
        });
        navigation.addView(completeButton, weightedButton());
        Button next = button("下一张");
        next.setOnClickListener(v -> {
            imageIndex = (imageIndex + 1) % imageUris.size();
            showCurrentImage();
        });
        navigation.addView(next, weightedButton());
        panel.addView(navigation, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)));

        attachOverlay(panel, dp(350), dp(390));
        showCurrentImage();
    }

    private LinearLayout basePanel(String title, String dragLabel) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(8), dp(7), dp(8), dp(8));
        panel.setBackground(rounded(Color.argb(238, 18, 18, 21), 20, 1, Color.argb(210, 92, 95, 104)));
        LinearLayout header = controlRow();
        TextView handle = text("≡  " + title + " · " + dragLabel, 13, Color.WHITE);
        header.addView(handle, new LinearLayout.LayoutParams(0, dp(40), 1f));
        Button size = button("缩放");
        size.setOnClickListener(v -> cycleSize());
        header.addView(size, new LinearLayout.LayoutParams(dp(58), dp(38)));
        Button close = button("×");
        close.setTextSize(22);
        close.setOnClickListener(v -> stopSelf());
        header.addView(close, new LinearLayout.LayoutParams(dp(46), dp(38)));
        panel.addView(header);
        handle.setOnTouchListener((v, event) -> dragOverlay(event));
        return panel;
    }

    private void attachOverlay(View panel, int width, int height) {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        windowParams = new WindowManager.LayoutParams(width, height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT);
        windowParams.gravity = Gravity.TOP | Gravity.START;
        windowParams.x = dp(18);
        windowParams.y = dp(100);
        overlay = panel;
        windowManager.addView(overlay, windowParams);
    }

    private boolean dragOverlay(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            dragStartX = windowParams.x;
            dragStartY = windowParams.y;
            touchStartX = event.getRawX();
            touchStartY = event.getRawY();
            return true;
        }
        if (event.getAction() == MotionEvent.ACTION_MOVE) {
            windowParams.x = dragStartX + Math.round(event.getRawX() - touchStartX);
            windowParams.y = dragStartY + Math.round(event.getRawY() - touchStartY);
            windowManager.updateViewLayout(overlay, windowParams);
            return true;
        }
        return event.getAction() == MotionEvent.ACTION_UP;
    }

    private void cycleSize() {
        sizeMode = (sizeMode + 1) % 3;
        int width = sizeMode == 0 ? 350 : sizeMode == 1 ? 270 : 410;
        int height;
        if (videoView != null) height = sizeMode == 0 ? 360 : sizeMode == 1 ? 300 : 430;
        else height = sizeMode == 0 ? 390 : sizeMode == 1 ? 320 : 470;
        windowParams.width = dp(width);
        windowParams.height = dp(height);
        windowManager.updateViewLayout(overlay, windowParams);
    }

    private void showCurrentImage() {
        if (imageView == null || imageUris.isEmpty()) return;
        int generation = ++imageGeneration;
        Uri uri = Uri.parse(imageUris.get(imageIndex));
        imageExecutor.execute(() -> {
            Bitmap decoded = decodeScaled(uri, 1400);
            handler.post(() -> {
                if (generation != imageGeneration || imageView == null) {
                    if (decoded != null) decoded.recycle();
                    return;
                }
                Bitmap previous = displayedBitmap;
                displayedBitmap = decoded;
                imageView.setImageBitmap(decoded);
                if (previous != null && previous != decoded) previous.recycle();
            });
        });
        boolean done = completedImages.contains(imageIndex);
        imageCounter.setText((imageIndex + 1) + " / " + imageUris.size() + "  ·  已完成 " + completedImages.size());
        completeButton.setText(done ? "取消完成" : "标记完成");
    }

    private Bitmap decodeScaled(Uri uri, int targetEdge) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream stream = getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(stream, null, bounds);
            }
            int largest = Math.max(bounds.outWidth, bounds.outHeight);
            int sample = 1;
            while (largest / (sample * 2) >= targetEdge) sample *= 2;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            try (InputStream stream = getContentResolver().openInputStream(uri)) {
                return BitmapFactory.decodeStream(stream, null, options);
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    private void applyPlaybackSpeed() {
        if (mediaPlayer == null) return;
        try { mediaPlayer.setPlaybackParams(mediaPlayer.getPlaybackParams().setSpeed(playbackSpeed)); }
        catch (RuntimeException ignored) {}
    }

    private String formatTime(int millis) {
        int seconds = Math.max(0, millis / 1000);
        return String.format(Locale.CHINA, "%02d:%02d", seconds / 60, seconds % 60);
    }

    private LinearLayout controlRow() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private LinearLayout.LayoutParams weightedButton() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(38), 1f);
        params.setMargins(dp(2), dp(2), dp(2), dp(2));
        return params;
    }

    private Button button(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextColor(Color.WHITE);
        button.setTextSize(11);
        button.setAllCaps(false);
        button.setPadding(0, 0, 0, 0);
        button.setBackground(rounded(Color.argb(215, 53, 54, 60), 12, 0, 0));
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

    private Notification notification(String mode) {
        Intent reopen = new Intent(this, FloatingHubActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 3, reopen,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("镜界创作" + mode + "运行中")
            .setContentText("悬浮窗可播放、切换和关闭")
            .setOngoing(true)
            .setContentIntent(pending)
            .build();
    }

    private void createChannel() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "悬浮跟拍", NotificationManager.IMPORTANCE_LOW));
    }

    private void removeOverlay() {
        imageGeneration++;
        handler.removeCallbacksAndMessages(null);
        releaseVideo();
        videoView = null;
        imageView = null;
        imageCounter = null;
        completeButton = null;
        if (overlay != null && windowManager != null) {
            try { windowManager.removeView(overlay); } catch (RuntimeException ignored) {}
        }
        overlay = null;
    }

    @Override public void onDestroy() {
        removeOverlay();
        imageExecutor.shutdownNow();
        if (displayedBitmap != null) displayedBitmap.recycle();
        displayedBitmap = null;
        super.onDestroy();
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
