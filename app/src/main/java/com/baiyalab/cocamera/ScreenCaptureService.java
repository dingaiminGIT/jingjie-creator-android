package com.baiyalab.cocamera;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class ScreenCaptureService extends Service {
    public static final String ACTION_START = "com.baiyalab.cocamera.SCREEN_START";
    public static final String ACTION_STOP = "com.baiyalab.cocamera.SCREEN_STOP";
    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";
    private static final String CHANNEL = "screen_creator";
    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private MediaRecorder recorder;
    private ParcelFileDescriptor outputDescriptor;
    private Uri outputUri;
    private boolean recording;

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        if (ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(intent.getAction()) && !recording) {
            startForeground(2001, createNotification());
            startCapture(intent);
        }
        return START_NOT_STICKY;
    }

    private void startCapture(Intent intent) {
        try {
            int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
            Intent resultData = Build.VERSION.SDK_INT >= 33
                ? intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class)
                : intent.getParcelableExtra(EXTRA_RESULT_DATA);
            if (resultData == null) throw new IllegalStateException("缺少系统录屏授权");

            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, "JingJie_Screen_" +
                new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(new Date()));
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            values.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/镜界创作");
            values.put(MediaStore.Video.Media.IS_PENDING, 1);
            outputUri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
            if (outputUri == null) throw new IllegalStateException("无法创建录屏文件");
            outputDescriptor = getContentResolver().openFileDescriptor(outputUri, "rw");
            if (outputDescriptor == null) throw new IllegalStateException("无法打开录屏文件");

            WindowManager windowManager = getSystemService(WindowManager.class);
            int sourceWidth;
            int sourceHeight;
            int density;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Rect bounds = windowManager.getMaximumWindowMetrics().getBounds();
                sourceWidth = bounds.width();
                sourceHeight = bounds.height();
                density = getResources().getConfiguration().densityDpi;
            } else {
                DisplayMetrics metrics = new DisplayMetrics();
                windowManager.getDefaultDisplay().getRealMetrics(metrics);
                sourceWidth = metrics.widthPixels;
                sourceHeight = metrics.heightPixels;
                density = metrics.densityDpi;
            }
            int width = Math.min(720, sourceWidth);
            width -= width % 2;
            int height = Math.round(width * sourceHeight / (float) sourceWidth);
            height -= height % 2;
            int scaledDensity = Math.max(1, Math.round(density * width / (float) sourceWidth));

            recorder = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(this) : new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setOutputFile(outputDescriptor.getFileDescriptor());
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            recorder.setVideoEncodingBitRate(6_000_000);
            recorder.setVideoFrameRate(30);
            recorder.setVideoSize(width, height);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioEncodingBitRate(128_000);
            recorder.setAudioSamplingRate(44_100);
            recorder.prepare();

            MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
            projection = manager.getMediaProjection(resultCode, resultData);
            if (projection == null) throw new IllegalStateException("系统拒绝录屏授权");
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() { stopSelf(); }
            }, new android.os.Handler(getMainLooper()));
            virtualDisplay = projection.createVirtualDisplay("镜界创作录屏", width, height, scaledDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, recorder.getSurface(), null, null);
            recorder.start();
            recording = true;
            Toast.makeText(this, "录屏讲解已开始，通知栏可停止", Toast.LENGTH_LONG).show();
        } catch (Exception error) {
            Toast.makeText(this, "录屏启动失败：" + error.getMessage(), Toast.LENGTH_LONG).show();
            stopSelf();
        }
    }

    private Notification createNotification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "录屏讲解", NotificationManager.IMPORTANCE_LOW));
        Intent stop = new Intent(this, ScreenCaptureService.class).setAction(ACTION_STOP);
        PendingIntent stopIntent = PendingIntent.getService(this, 2, stop,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("镜界创作正在录屏讲解")
            .setContentText("正在录制屏幕和麦克风")
            .addAction(0, "停止并保存", stopIntent)
            .setOngoing(true)
            .build();
    }

    @Override public void onDestroy() {
        finishCapture();
        super.onDestroy();
    }

    private void finishCapture() {
        if (virtualDisplay != null) virtualDisplay.release();
        virtualDisplay = null;
        boolean validOutput = false;
        if (recording && recorder != null) {
            try {
                recorder.stop();
                validOutput = true;
            } catch (RuntimeException ignored) {}
        }
        recording = false;
        if (recorder != null) recorder.release();
        recorder = null;
        if (projection != null) projection.stop();
        projection = null;
        if (outputUri != null) {
            if (validOutput) {
                ContentValues ready = new ContentValues();
                ready.put(MediaStore.Video.Media.IS_PENDING, 0);
                getContentResolver().update(outputUri, ready, null, null);
                Toast.makeText(this, "录屏已保存到 Movies/镜界创作", Toast.LENGTH_LONG).show();
            } else {
                getContentResolver().delete(outputUri, null, null);
            }
        }
        try { if (outputDescriptor != null) outputDescriptor.close(); } catch (Exception ignored) {}
        outputDescriptor = null;
        outputUri = null;
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }
}
