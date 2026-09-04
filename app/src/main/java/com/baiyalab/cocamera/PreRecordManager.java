package com.baiyalab.cocamera;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Log;

import androidx.camera.video.FileOutputOptions;
import androidx.camera.video.PendingRecording;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Keeps three rolling five-second clips and joins them with the live take. */
public final class PreRecordManager {
    public interface Listener {
        void onBuffering(String message);
        void onCaptureStarted();
        void onSaved(Uri uri);
        void onError(String message);
    }

    private static final long SEGMENT_MS = 5_000;
    private final Context context;
    private final VideoCapture<Recorder> videoCapture;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService mergeExecutor = Executors.newSingleThreadExecutor();
    private final ArrayDeque<File> preSegments = new ArrayDeque<>();
    private Recording currentRecording;
    private File currentFile;
    private boolean currentIsLive;
    private boolean captureRequested;
    private boolean stopRequested;
    private boolean disposed;

    private final Runnable rotateSegment = () -> {
        if (!disposed && !captureRequested && currentRecording != null) currentRecording.stop();
    };

    public PreRecordManager(Context context, VideoCapture<Recorder> videoCapture, Listener listener) {
        this.context = context.getApplicationContext();
        this.videoCapture = videoCapture;
        this.listener = listener;
    }

    public void startBuffering() {
        if (disposed || currentRecording != null) return;
        startRecording(false);
    }

    public boolean isCapturing() {
        return captureRequested;
    }

    public void triggerCapture() {
        if (disposed || captureRequested) return;
        captureRequested = true;
        handler.removeCallbacks(rotateSegment);
        listener.onCaptureStarted();
        if (currentRecording != null) currentRecording.stop(); else startRecording(true);
    }

    public void stopCapture() {
        if (!captureRequested || disposed) return;
        stopRequested = true;
        if (currentRecording != null && currentIsLive) currentRecording.stop();
    }

    public void dispose() {
        disposed = true;
        handler.removeCallbacksAndMessages(null);
        if (currentRecording != null) currentRecording.stop();
        currentRecording = null;
        for (File file : preSegments) file.delete();
        preSegments.clear();
        mergeExecutor.shutdownNow();
    }

    private void startRecording(boolean live) {
        if (disposed) return;
        try {
            File file = File.createTempFile(live ? "live_" : "buffer_", ".mp4", context.getCacheDir());
            FileOutputOptions options = new FileOutputOptions.Builder(file).build();
            PendingRecording pending = videoCapture.getOutput().prepareRecording(context, options);
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                pending = pending.withAudioEnabled();
            }
            currentFile = file;
            currentIsLive = live;
            currentRecording = pending.start(ContextCompat.getMainExecutor(context), event -> handleEvent(event, file, live));
        } catch (Exception error) {
            listener.onError("预录启动失败：" + error.getMessage());
        }
    }

    private void handleEvent(VideoRecordEvent event, File file, boolean live) {
        if (event instanceof VideoRecordEvent.Start) {
            if (!live) {
                listener.onBuffering("正在循环预录 · 已缓存最近最多 15 秒");
                handler.postDelayed(rotateSegment, SEGMENT_MS);
            }
            return;
        }
        if (!(event instanceof VideoRecordEvent.Finalize)) return;
        handler.removeCallbacks(rotateSegment);
        currentRecording = null;
        currentFile = null;
        VideoRecordEvent.Finalize result = (VideoRecordEvent.Finalize) event;
        if (disposed) {
            file.delete();
            return;
        }
        if (result.hasError() && result.getError() != VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA) {
            file.delete();
            listener.onError("预录分段失败：" + result.getError());
            return;
        }
        if (!file.exists() || file.length() == 0) {
            file.delete();
        } else if (live) {
            List<File> clips = new ArrayList<>(preSegments);
            clips.add(file);
            mergeExecutor.execute(() -> mergeAndPublish(clips));
            preSegments.clear();
            return;
        } else {
            preSegments.addLast(file);
            while (preSegments.size() > 3) {
                File oldest = preSegments.removeFirst();
                oldest.delete();
            }
        }

        if (captureRequested) {
            startRecording(true);
            if (stopRequested) handler.postDelayed(this::stopCapture, 300);
        } else {
            startRecording(false);
        }
    }

    private void mergeAndPublish(List<File> clips) {
        ContentResolver resolver = context.getContentResolver();
        Uri outputUri = null;
        ParcelFileDescriptor descriptor = null;
        MediaMuxer muxer = null;
        try {
            if (clips.isEmpty()) throw new IllegalStateException("没有可合并的预录片段");
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, "JingJie_PreRecord_" +
                new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(new Date()));
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            values.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/镜界创作");
            values.put(MediaStore.Video.Media.IS_PENDING, 1);
            outputUri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
            if (outputUri == null) throw new IllegalStateException("无法创建预录视频");
            descriptor = resolver.openFileDescriptor(outputUri, "rw");
            if (descriptor == null) throw new IllegalStateException("无法打开预录视频");
            muxer = new MediaMuxer(descriptor.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            MediaExtractor template = new MediaExtractor();
            template.setDataSource(clips.get(0).getAbsolutePath());
            int[] outputTracks = new int[template.getTrackCount()];
            for (int i = 0; i < template.getTrackCount(); i++) outputTracks[i] = muxer.addTrack(template.getTrackFormat(i));
            template.release();
            muxer.start();

            long segmentOffsetUs = 0;
            ByteBuffer sampleBuffer = ByteBuffer.allocateDirect(4 * 1024 * 1024);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            for (File clip : clips) {
                MediaExtractor probe = new MediaExtractor();
                probe.setDataSource(clip.getAbsolutePath());
                long segmentDurationUs = 0;
                for (int track = 0; track < probe.getTrackCount(); track++) {
                    MediaExtractor extractor = new MediaExtractor();
                    extractor.setDataSource(clip.getAbsolutePath());
                    extractor.selectTrack(track);
                    while (true) {
                        sampleBuffer.clear();
                        int size = extractor.readSampleData(sampleBuffer, 0);
                        if (size < 0) break;
                        long sampleTime = extractor.getSampleTime();
                        int extractorFlags = extractor.getSampleFlags();
                        int codecFlags = (extractorFlags & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                            ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                        info.set(0, size, segmentOffsetUs + Math.max(0, sampleTime), codecFlags);
                        muxer.writeSampleData(outputTracks[track], sampleBuffer, info);
                        segmentDurationUs = Math.max(segmentDurationUs, sampleTime);
                        extractor.advance();
                    }
                    extractor.release();
                }
                probe.release();
                segmentOffsetUs += segmentDurationUs + 33_333;
            }
            muxer.stop();
            ContentValues ready = new ContentValues();
            ready.put(MediaStore.Video.Media.IS_PENDING, 0);
            resolver.update(outputUri, ready, null, null);
            Uri saved = outputUri;
            handler.post(() -> listener.onSaved(saved));
        } catch (Exception error) {
            Log.e("PreRecordManager", "Unable to publish rolling recording", error);
            if (outputUri != null) resolver.delete(outputUri, null, null);
            handler.post(() -> listener.onError("合并预录视频失败：" + error.getMessage()));
        } finally {
            try { if (muxer != null) muxer.release(); } catch (Exception ignored) {}
            try { if (descriptor != null) descriptor.close(); } catch (Exception ignored) {}
            for (File clip : clips) clip.delete();
        }
    }
}
