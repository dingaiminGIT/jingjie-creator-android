package com.baiyalab.cocamera;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Log;

import androidx.camera.core.ImageProxy;
import androidx.core.content.ContextCompat;

import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** CPU compositing recorder used for front/back concurrent camera output. */
public final class DualCompositeRecorder {
    public interface Listener {
        void onStarted();
        void onFinished(Uri uri);
        void onError(String message);
    }

    private static final int WIDTH = 720;
    private static final int HEIGHT = 1280;
    private static final int FRAME_RATE = 24;
    private static final int VIDEO_BITRATE = 6_000_000;
    private static final int AUDIO_RATE = 44_100;

    private final Context context;
    private final float splitRatio;
    private final boolean mirrorPrimary;
    private volatile boolean swapCameras;
    private final RectF normalizedPipBounds;
    private final Supplier<String> watermarkSupplier;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService encoderExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile long stopRequestedNs;
    private final Object frameLock = new Object();
    private Bitmap backFrame;
    private Bitmap frontFrame;

    public DualCompositeRecorder(Context context, boolean splitLayout,
                                 Supplier<String> watermarkSupplier, Listener listener) {
        this(context, splitLayout ? 0.5f : 0f, false, false,
            new RectF(0.58f, 0.04f, 0.96f, 0.35f), watermarkSupplier, listener);
    }

    public DualCompositeRecorder(Context context, boolean splitLayout, boolean mirrorPrimary,
                                 Supplier<String> watermarkSupplier, Listener listener) {
        this(context, splitLayout ? 0.5f : 0f, mirrorPrimary, false,
            new RectF(0.58f, 0.04f, 0.96f, 0.35f), watermarkSupplier, listener);
    }

    public DualCompositeRecorder(Context context, float splitRatio, boolean mirrorPrimary, boolean swapCameras,
                                 Supplier<String> watermarkSupplier, Listener listener) {
        this(context, splitRatio, mirrorPrimary, swapCameras,
            new RectF(0.58f, 0.04f, 0.96f, 0.35f), watermarkSupplier, listener);
    }

    public DualCompositeRecorder(Context context, float splitRatio, boolean mirrorPrimary, boolean swapCameras,
                                 RectF normalizedPipBounds, Supplier<String> watermarkSupplier, Listener listener) {
        this.context = context.getApplicationContext();
        this.splitRatio = splitRatio;
        this.mirrorPrimary = mirrorPrimary;
        this.swapCameras = swapCameras;
        this.normalizedPipBounds = new RectF(normalizedPipBounds);
        this.watermarkSupplier = watermarkSupplier;
        this.listener = listener;
    }

    public boolean isRunning() {
        return running.get();
    }

    public void start() {
        if (!running.compareAndSet(false, true)) return;
        encoderExecutor.execute(this::encodeLoop);
    }

    public void stop() {
        stopRequestedNs = System.nanoTime();
        running.set(false);
    }

    public void setSwapCameras(boolean swapCameras) {
        this.swapCameras = swapCameras;
    }

    public void offerBack(ImageProxy image) {
        offer(image, false);
    }

    public void offerFront(ImageProxy image) {
        offer(image, true);
    }

    private void offer(ImageProxy image, boolean front) {
        try {
            if (!running.get()) return;
            Bitmap bitmap = image.toBitmap();
            int rotation = image.getImageInfo().getRotationDegrees();
            if (rotation != 0) {
                Matrix matrix = new Matrix();
                matrix.postRotate(rotation);
                Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
                if (rotated != bitmap) bitmap.recycle();
                bitmap = rotated;
            }
            synchronized (frameLock) {
                Bitmap previous = front ? frontFrame : backFrame;
                if (front) frontFrame = bitmap; else backFrame = bitmap;
                if (previous != null && previous != bitmap) previous.recycle();
            }
        } catch (RuntimeException ignored) {
            // A dropped analysis frame must never block the camera pipeline.
        } finally {
            image.close();
        }
    }

    private void encodeLoop() {
        ContentResolver resolver = context.getContentResolver();
        Uri outputUri = null;
        ParcelFileDescriptor outputFd = null;
        MediaMuxer muxer = null;
        MediaCodec videoCodec = null;
        MediaCodec audioCodec = null;
        AudioRecord audioRecord = null;
        boolean muxerStarted = false;
        boolean audioRecordStopped = false;
        boolean audioEnabled = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED;
        long timelineStartNs = System.nanoTime();
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, "JingJie_Dual_" +
                new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(new Date()));
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            values.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/镜界创作");
            values.put(MediaStore.Video.Media.IS_PENDING, 1);
            outputUri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
            if (outputUri == null) throw new IllegalStateException("无法创建视频文件");
            outputFd = resolver.openFileDescriptor(outputUri, "rw");
            if (outputFd == null) throw new IllegalStateException("无法打开视频文件");
            muxer = new MediaMuxer(outputFd.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            videoCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            int colorFormat = chooseColorFormat(videoCodec.getCodecInfo());
            MediaFormat videoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT);
            videoFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat);
            videoFormat.setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE);
            videoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
            videoFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            videoCodec.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            videoCodec.start();

            if (audioEnabled) {
                int minBuffer = AudioRecord.getMinBufferSize(AUDIO_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
                // Keep about one second of capture headroom. CPU composition can
                // occasionally occupy the encoder thread for tens of milliseconds.
                int recordBufferBytes = Math.max(minBuffer * 4, AUDIO_RATE * 2);
                audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, AUDIO_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, recordBufferBytes);
                if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                    audioRecord.release();
                    audioRecord = null;
                    audioEnabled = false;
                } else {
                    MediaFormat audioFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, AUDIO_RATE, 1);
                    audioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
                    audioFormat.setInteger(MediaFormat.KEY_BIT_RATE, 96_000);
                    audioCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
                    audioCodec.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                    audioCodec.start();
                    timelineStartNs = System.nanoTime();
                    audioRecord.startRecording();
                }
            }

            mainHandler.post(listener::onStarted);
            Bitmap composite = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
            int[] pixels = new int[WIDTH * HEIGHT];
            byte[] yuv = new byte[WIDTH * HEIGHT * 3 / 2];
            byte[] audioBytes = new byte[4096];
            long frameIntervalNs = 1_000_000_000L / FRAME_RATE;
            long nextFrameNs = timelineStartNs;
            long lastVideoPtsUs = -1;
            long audioSamples = 0;
            int pendingAudioOffset = 0;
            int pendingAudioBytes = 0;
            int videoTrack = -1;
            int audioTrack = audioEnabled ? -1 : -2;
            boolean videoEosQueued = false;
            boolean audioEosQueued = !audioEnabled;
            boolean videoDone = false;
            boolean audioDone = !audioEnabled;
            MediaCodec.BufferInfo videoInfo = new MediaCodec.BufferInfo();
            MediaCodec.BufferInfo audioInfo = new MediaCodec.BufferInfo();

            while (!videoDone || !audioDone) {
                boolean captureRunning = running.get();
                if (captureRunning) {
                    long now = System.nanoTime();
                    if (now >= nextFrameNs && hasBackFrame()) {
                        drawComposite(composite);
                        composite.getPixels(pixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT);
                        boolean semiPlanar = colorFormat != MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar;
                        argbToYuv420(pixels, yuv, semiPlanar);
                        int inputIndex = videoCodec.dequeueInputBuffer(0);
                        if (inputIndex >= 0) {
                            ByteBuffer input = videoCodec.getInputBuffer(inputIndex);
                            if (input != null) {
                                input.clear();
                                input.put(yuv);
                                // Preserve real elapsed capture time. A missed CPU
                                // composition deadline must not stretch the video clock.
                                long pts = Math.max(lastVideoPtsUs + 1,
                                    (now - timelineStartNs) / 1_000L);
                                videoCodec.queueInputBuffer(inputIndex, 0, yuv.length, pts, 0);
                                lastVideoPtsUs = pts;
                                nextFrameNs = Math.max(nextFrameNs + frameIntervalNs, System.nanoTime());
                            }
                        }
                    }
                } else {
                    if (!videoEosQueued) {
                        int inputIndex = videoCodec.dequeueInputBuffer(10_000);
                        if (inputIndex >= 0) {
                            long stopNs = stopRequestedNs > 0 ? stopRequestedNs : System.nanoTime();
                            long videoEosPtsUs = Math.max(lastVideoPtsUs + 1,
                                (stopNs - timelineStartNs) / 1_000L);
                            videoCodec.queueInputBuffer(inputIndex, 0, 0,
                                videoEosPtsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            videoEosQueued = true;
                        }
                    }
                }

                if (audioEnabled && audioRecord != null && audioCodec != null && !audioEosQueued) {
                    long targetSamples = Long.MAX_VALUE;
                    if (!captureRunning) {
                        long stopNs = stopRequestedNs > 0 ? stopRequestedNs : System.nanoTime();
                        targetSamples = Math.max(audioSamples,
                            (stopNs - timelineStartNs) * AUDIO_RATE / 1_000_000_000L);
                    }

                    // Retain PCM until an AAC input buffer is available. Previously
                    // a whole microphone chunk was discarded whenever the codec was
                    // briefly busy, which continuously shortened the audio track.
                    if (pendingAudioBytes == 0 && audioSamples < targetSamples) {
                        int bytesToRead = audioBytes.length;
                        if (!captureRunning) {
                            long remainingBytes = (targetSamples - audioSamples) * 2L;
                            bytesToRead = (int) Math.min(bytesToRead, remainingBytes);
                        }
                        int read = bytesToRead > 0
                            ? audioRecord.read(audioBytes, 0, bytesToRead, AudioRecord.READ_NON_BLOCKING) : 0;
                        if (read > 0) {
                            pendingAudioOffset = 0;
                            pendingAudioBytes = read - (read & 1);
                        }
                    }

                    if (pendingAudioBytes > 0) {
                        int inputIndex = audioCodec.dequeueInputBuffer(0);
                        if (inputIndex >= 0) {
                            ByteBuffer input = audioCodec.getInputBuffer(inputIndex);
                            if (input != null) {
                                input.clear();
                                int bytesToWrite = Math.min(pendingAudioBytes, input.remaining());
                                if (!captureRunning) {
                                    long remainingBytes = (targetSamples - audioSamples) * 2L;
                                    bytesToWrite = (int) Math.min(bytesToWrite, Math.max(0L, remainingBytes));
                                }
                                if (bytesToWrite > 0) {
                                    input.put(audioBytes, pendingAudioOffset, bytesToWrite);
                                    long pts = audioSamples * 1_000_000L / AUDIO_RATE;
                                    audioCodec.queueInputBuffer(inputIndex, 0, bytesToWrite, pts, 0);
                                    audioSamples += bytesToWrite / 2;
                                    pendingAudioOffset += bytesToWrite;
                                    pendingAudioBytes -= bytesToWrite;
                                } else {
                                    audioCodec.queueInputBuffer(inputIndex, 0, 0,
                                        audioSamples * 1_000_000L / AUDIO_RATE, 0);
                                    pendingAudioBytes = 0;
                                }
                            }
                        }
                    }

                    if (!captureRunning && audioSamples >= targetSamples && pendingAudioBytes == 0) {
                        if (!audioRecordStopped) {
                            audioRecord.stop();
                            audioRecordStopped = true;
                        }
                        int inputIndex = audioCodec.dequeueInputBuffer(10_000);
                        if (inputIndex >= 0) {
                            audioCodec.queueInputBuffer(inputIndex, 0, 0,
                                audioSamples * 1_000_000L / AUDIO_RATE, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            audioEosQueued = true;
                        }
                    }
                }

                DrainResult videoResult = drain(videoCodec, videoInfo, muxer, videoTrack, muxerStarted);
                videoTrack = videoResult.track;
                videoDone |= videoResult.eos;
                if (audioEnabled && audioCodec != null) {
                    DrainResult audioResult = drain(audioCodec, audioInfo, muxer, audioTrack, muxerStarted);
                    audioTrack = audioResult.track;
                    audioDone |= audioResult.eos;
                }
                if (!muxerStarted && videoTrack >= 0 && (!audioEnabled || audioTrack >= 0)) {
                    muxer.start();
                    muxerStarted = true;
                }
                if (running.get()) Thread.sleep(2);
            }

            if (audioRecord != null && !audioRecordStopped) audioRecord.stop();
            if (muxerStarted) muxer.stop();
            ContentValues ready = new ContentValues();
            ready.put(MediaStore.Video.Media.IS_PENDING, 0);
            resolver.update(outputUri, ready, null, null);
            Uri finalUri = outputUri;
            mainHandler.post(() -> listener.onFinished(finalUri));
        } catch (Exception error) {
            Log.e("DualCompositeRecorder", "Composite recording failed", error);
            if (outputUri != null) resolver.delete(outputUri, null, null);
            String message = error.getClass().getSimpleName() + (error.getMessage() == null ? "" : "：" + error.getMessage());
            mainHandler.post(() -> listener.onError(message));
        } finally {
            running.set(false);
            safeRelease(audioRecord, audioCodec, videoCodec, muxer, outputFd);
            synchronized (frameLock) {
                if (backFrame != null) backFrame.recycle();
                if (frontFrame != null) frontFrame.recycle();
                backFrame = null;
                frontFrame = null;
            }
            encoderExecutor.shutdown();
        }
    }

    private boolean hasBackFrame() {
        synchronized (frameLock) { return backFrame != null; }
    }

    private void drawComposite(Bitmap output) {
        Canvas canvas = new Canvas(output);
        canvas.drawColor(Color.BLACK);
        synchronized (frameLock) {
            Bitmap primary = swapCameras ? frontFrame : backFrame;
            Bitmap secondary = swapCameras ? backFrame : frontFrame;
            boolean primaryMirror = swapCameras || mirrorPrimary;
            boolean secondaryMirror = !swapCameras;
            if (splitRatio > 0f) {
                float divider = WIDTH * splitRatio;
                RectF left = new RectF(0, 0, divider, HEIGHT);
                RectF right = new RectF(divider, 0, WIDTH, HEIGHT);
                if (primary != null) drawCenterCrop(canvas, primary, left, primaryMirror, 0);
                if (secondary != null) drawCenterCrop(canvas, secondary, right, secondaryMirror, 0);
            } else if (splitRatio < 0f) {
                float divider = HEIGHT * -splitRatio;
                RectF top = new RectF(0, 0, WIDTH, divider);
                RectF bottom = new RectF(0, divider, WIDTH, HEIGHT);
                if (primary != null) drawCenterCrop(canvas, primary, top, primaryMirror, 0);
                if (secondary != null) drawCenterCrop(canvas, secondary, bottom, secondaryMirror, 0);
            } else {
                if (primary != null) drawCenterCrop(canvas, primary, new RectF(0, 0, WIDTH, HEIGHT), primaryMirror, 0);
                if (secondary != null) {
                    RectF pip = new RectF(normalizedPipBounds.left * WIDTH, normalizedPipBounds.top * HEIGHT,
                        normalizedPipBounds.right * WIDTH, normalizedPipBounds.bottom * HEIGHT);
                    Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
                    border.setColor(Color.WHITE);
                    canvas.drawRoundRect(new RectF(pip.left - 5, pip.top - 5, pip.right + 5, pip.bottom + 5), 28, 28, border);
                    drawCenterCrop(canvas, secondary, pip, secondaryMirror, 24);
                }
            }
        }
        String watermark = watermarkSupplier == null ? null : watermarkSupplier.get();
        if (watermark != null && !watermark.trim().isEmpty()) drawWatermark(canvas, watermark);
    }

    private void drawCenterCrop(Canvas canvas, Bitmap bitmap, RectF destination, boolean mirror, float radius) {
        float sourceAspect = bitmap.getWidth() / (float) bitmap.getHeight();
        float destinationAspect = destination.width() / destination.height();
        Rect source;
        if (sourceAspect > destinationAspect) {
            int wantedWidth = Math.round(bitmap.getHeight() * destinationAspect);
            int left = (bitmap.getWidth() - wantedWidth) / 2;
            source = new Rect(left, 0, left + wantedWidth, bitmap.getHeight());
        } else {
            int wantedHeight = Math.round(bitmap.getWidth() / destinationAspect);
            int top = (bitmap.getHeight() - wantedHeight) / 2;
            source = new Rect(0, top, bitmap.getWidth(), top + wantedHeight);
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

    private void drawWatermark(Canvas canvas, String watermark) {
        String[] rawLines = watermark.split("\\n");
        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setTextSize(25);
        text.setColor(Color.WHITE);
        String[] lines = new String[rawLines.length];
        float available = WIDTH - 86;
        for (int i = 0; i < rawLines.length; i++) {
            String line = rawLines[i];
            if (text.measureText(line) > available) {
                int count = text.breakText(line, true, available - text.measureText("…"), null);
                line = line.substring(0, Math.max(0, count)) + "…";
            }
            lines[i] = line;
        }
        Paint background = new Paint(Paint.ANTI_ALIAS_FLAG);
        background.setColor(Color.argb(155, 0, 0, 0));
        float maxWidth = 0;
        for (String line : lines) maxWidth = Math.max(maxWidth, text.measureText(line));
        float top = HEIGHT - 44 - lines.length * 34f;
        canvas.drawRoundRect(new RectF(24, top - 18, 54 + maxWidth, HEIGHT - 22), 16, 16, background);
        float baseline = top + 12;
        for (String line : lines) {
            canvas.drawText(line, 39, baseline, text);
            baseline += 34;
        }
    }

    private int chooseColorFormat(MediaCodecInfo info) {
        int[] formats = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).colorFormats;
        for (int candidate : new int[] {
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
        }) {
            for (int supported : formats) if (supported == candidate) return candidate;
        }
        throw new IllegalStateException("设备编码器不支持 YUV420 输入：" + Arrays.toString(formats));
    }

    private void argbToYuv420(int[] argb, byte[] output, boolean semiPlanar) {
        int frameSize = WIDTH * HEIGHT;
        int yIndex = 0;
        int uIndex = frameSize;
        int vIndex = frameSize + frameSize / 4;
        int uvIndex = frameSize;
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                int color = argb[y * WIDTH + x];
                int r = (color >> 16) & 0xff;
                int g = (color >> 8) & 0xff;
                int b = color & 0xff;
                int yy = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
                output[yIndex++] = (byte) clamp(yy);
                if ((y & 1) == 0 && (x & 1) == 0) {
                    int u = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
                    int v = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;
                    if (semiPlanar) {
                        output[uvIndex++] = (byte) clamp(u);
                        output[uvIndex++] = (byte) clamp(v);
                    } else {
                        output[uIndex++] = (byte) clamp(u);
                        output[vIndex++] = (byte) clamp(v);
                    }
                }
            }
        }
    }

    private int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private DrainResult drain(MediaCodec codec, MediaCodec.BufferInfo info, MediaMuxer muxer,
                              int track, boolean muxerStarted) {
        boolean eos = false;
        while (true) {
            int outputIndex = codec.dequeueOutputBuffer(info, 0);
            if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) break;
            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (track < 0) track = muxer.addTrack(codec.getOutputFormat());
                break;
            }
            if (outputIndex >= 0) {
                ByteBuffer output = codec.getOutputBuffer(outputIndex);
                if (output != null && info.size > 0 && muxerStarted &&
                    (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    output.position(info.offset);
                    output.limit(info.offset + info.size);
                    muxer.writeSampleData(track, output, info);
                }
                eos |= (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                codec.releaseOutputBuffer(outputIndex, false);
            }
        }
        return new DrainResult(track, eos);
    }

    private void safeRelease(AudioRecord audioRecord, MediaCodec audioCodec, MediaCodec videoCodec,
                             MediaMuxer muxer, ParcelFileDescriptor descriptor) {
        try { if (audioRecord != null) audioRecord.release(); } catch (Exception ignored) {}
        try { if (audioCodec != null) { audioCodec.stop(); audioCodec.release(); } } catch (Exception ignored) {}
        try { if (videoCodec != null) { videoCodec.stop(); videoCodec.release(); } } catch (Exception ignored) {}
        try { if (muxer != null) muxer.release(); } catch (Exception ignored) {}
        try { if (descriptor != null) descriptor.close(); } catch (Exception ignored) {}
    }

    private static final class DrainResult {
        final int track;
        final boolean eos;
        DrainResult(int track, boolean eos) { this.track = track; this.eos = eos; }
    }
}
