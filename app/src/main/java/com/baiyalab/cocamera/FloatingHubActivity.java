package com.baiyalab.cocamera;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;

public final class FloatingHubActivity extends AppCompatActivity {
    private String selectedMode = "prompt";
    private String pendingOverlayMode;
    private EditText scriptEditor;
    private String draftScript = "今天我们来介绍一个简单但非常实用的创作方法。\n\n看着镜头自然表达，提词内容会跟随你的语速向上滚动。";
    private boolean voiceFollowEnabled = true;
    private int promptSpeed = 2;
    private Uri selectedVideo;
    private final ArrayList<Uri> selectedImages = new ArrayList<>();

    private final ActivityResultLauncher<Intent> screenCaptureLauncher = registerForActivityResult(
        new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
                Toast.makeText(this, "已取消录屏", Toast.LENGTH_SHORT).show();
                return;
            }
            Intent service = new Intent(this, ScreenCaptureService.class)
                .setAction(ScreenCaptureService.ACTION_START)
                .putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.getResultCode())
                .putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.getData());
            ContextCompat.startForegroundService(this, service);
            moveTaskToBack(true);
        });

    private final ActivityResultLauncher<String[]> videoPicker = registerForActivityResult(
        new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri == null) return;
            selectedVideo = uri;
            persistReadPermission(uri);
            selectedMode = "video";
            buildUi();
        });

    private final ActivityResultLauncher<String[]> galleryPicker = registerForActivityResult(
        new ActivityResultContracts.OpenMultipleDocuments(), uris -> {
            if (uris == null || uris.isEmpty()) return;
            selectedImages.clear();
            int limit = Math.min(50, uris.size());
            for (int i = 0; i < limit; i++) {
                Uri uri = uris.get(i);
                selectedImages.add(uri);
                persistReadPermission(uri);
            }
            selectedMode = "gallery";
            buildUi();
        });

    private final ActivityResultLauncher<String> microphonePermission = registerForActivityResult(
        new ActivityResultContracts.RequestPermission(), granted -> {
            if (granted) ensureOverlayPermission("prompt");
            else {
                voiceFollowEnabled = false;
                Toast.makeText(this, "没有麦克风权限，将使用匀速滚动", Toast.LENGTH_LONG).show();
                ensureOverlayPermission("prompt");
            }
        });

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        String requestedMode = getIntent().getStringExtra("mode");
        if (requestedMode != null && (requestedMode.equals("screen") || requestedMode.equals("prompt")
            || requestedMode.equals("video") || requestedMode.equals("gallery"))) selectedMode = requestedMode;
        buildUi();
    }

    @Override protected void onResume() {
        super.onResume();
        if (pendingOverlayMode != null && Settings.canDrawOverlays(this)) {
            String mode = pendingOverlayMode;
            pendingOverlayMode = null;
            startSelectedOverlay(mode);
        }
    }

    private void buildUi() {
        if (scriptEditor != null) draftScript = scriptEditor.getText().toString();
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.BLACK);
        scroll.setFillViewport(true);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(18), dp(20), dp(18), dp(32));
        scroll.addView(page);

        TextView back = label("‹  悬浮创作", 27, Color.WHITE, true);
        back.setOnClickListener(v -> finish());
        page.addView(back);
        page.addView(label("选择工具，准备好后切到任意 App 继续创作", 14, Color.rgb(145, 148, 156), false));

        String[] modes = {"screen", "prompt", "video", "gallery"};
        String[] titles = {"录屏讲解", "悬浮提词", "视频跟拍", "图集跟拍"};
        String[] subtitles = {"录操作和声音", "看稿自然表达", "模仿动作节奏", "逐张复刻构图"};
        String[] marks = {"▰", "≡", "▶", "▣"};
        LinearLayout modeGrid = new LinearLayout(this);
        modeGrid.setOrientation(LinearLayout.VERTICAL);
        for (int rowIndex = 0; rowIndex < 2; rowIndex++) {
            LinearLayout row = new LinearLayout(this);
            for (int column = 0; column < 2; column++) {
                int index = rowIndex * 2 + column;
                String mode = modes[index];
                boolean selected = selectedMode.equals(mode);
                LinearLayout card = new LinearLayout(this);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setGravity(Gravity.CENTER_VERTICAL);
                card.setPadding(dp(15), dp(12), dp(12), dp(12));
                card.setBackground(rounded(selected ? Color.rgb(26, 47, 69) : Color.rgb(24, 24, 27),
                    19, selected ? 2 : 1, selected ? Color.rgb(47, 151, 255) : Color.rgb(50, 51, 56)));
                card.addView(label(marks[index], 20, selected ? Color.rgb(84, 172, 255) : Color.rgb(150, 154, 163), true));
                card.addView(label(titles[index], 17, Color.WHITE, true));
                card.addView(label(subtitles[index], 12, Color.rgb(148, 151, 159), false));
                card.setOnClickListener(v -> {
                    selectedMode = mode;
                    buildUi();
                });
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(106), 1f);
                params.setMargins(column == 0 ? 0 : dp(5), dp(10), column == 0 ? dp(5) : 0, 0);
                row.addView(card, params);
            }
            modeGrid.addView(row, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(116)));
        }
        LinearLayout.LayoutParams gridParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        gridParams.topMargin = dp(12);
        page.addView(modeGrid, gridParams);

        LinearLayout config = new LinearLayout(this);
        config.setOrientation(LinearLayout.VERTICAL);
        config.setPadding(dp(17), dp(17), dp(17), dp(18));
        config.setBackground(rounded(Color.rgb(20, 20, 23), 22, 1, Color.rgb(48, 49, 54)));
        LinearLayout.LayoutParams configParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        configParams.topMargin = dp(16);
        page.addView(config, configParams);

        if (selectedMode.equals("screen")) buildScreenConfig(config);
        else if (selectedMode.equals("prompt")) buildPromptConfig(config);
        else if (selectedMode.equals("video")) buildVideoConfig(config);
        else buildGalleryConfig(config);
        setContentView(scroll);
    }

    private void buildScreenConfig(LinearLayout parent) {
        parent.addView(label("录屏讲解", 25, Color.WHITE, true));
        parent.addView(description("录制网页、课件或其他 App 的操作过程，同时收录麦克风讲解。开始前 Android 会显示系统录屏确认。"));
        parent.addView(chipRow("屏幕 H.264", "麦克风 AAC", "通知栏停止"));
        Button start = primaryButton("开始录屏讲解");
        start.setOnClickListener(v -> startScreenCaptureRequest());
        parent.addView(start, actionParams());
    }

    private void buildPromptConfig(LinearLayout parent) {
        parent.addView(label("悬浮提词", 25, Color.WHITE, true));
        parent.addView(description("提词窗会浮在相机、直播、微信或浏览器上方。语音跟读会匹配你说出的文稿，匀速模式则按设定速度滚动。"));
        scriptEditor = new EditText(this);
        scriptEditor.setText(draftScript);
        scriptEditor.setTextColor(Color.WHITE);
        scriptEditor.setHintTextColor(Color.rgb(112, 114, 120));
        scriptEditor.setHint("粘贴提词文稿…");
        scriptEditor.setTextSize(18);
        scriptEditor.setGravity(Gravity.TOP);
        scriptEditor.setPadding(dp(14), dp(14), dp(14), dp(14));
        scriptEditor.setBackground(rounded(Color.rgb(29, 29, 33), 16, 1, Color.rgb(57, 58, 64)));
        LinearLayout.LayoutParams editorParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(190));
        editorParams.topMargin = dp(15);
        parent.addView(scriptEditor, editorParams);

        TextView speedLabel = label("匀速滚动速度  ·  " + promptSpeed, 13, Color.rgb(170, 173, 181), false);
        LinearLayout.LayoutParams speedLabelParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        speedLabelParams.topMargin = dp(14);
        parent.addView(speedLabel, speedLabelParams);
        SeekBar speed = new SeekBar(this);
        speed.setMax(5);
        speed.setProgress(promptSpeed - 1);
        speed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                promptSpeed = progress + 1;
                speedLabel.setText("匀速滚动速度  ·  " + promptSpeed);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        parent.addView(speed, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42)));

        Button followMode = secondaryButton(voiceFollowEnabled ? "✓ 语音跟读" : "匀速滚动");
        followMode.setOnClickListener(v -> {
            draftScript = scriptEditor.getText().toString();
            voiceFollowEnabled = !voiceFollowEnabled;
            buildUi();
        });
        parent.addView(followMode, actionParams());
        Button start = primaryButton("开启悬浮提词");
        start.setOnClickListener(v -> preparePromptOverlay());
        parent.addView(start, actionParams());
    }

    private void buildVideoConfig(LinearLayout parent) {
        parent.addView(label("视频跟拍", 25, Color.WHITE, true));
        parent.addView(description("选择参考视频后，它会以可移动小窗浮在拍摄 App 上方。支持播放暂停、前后 5 秒、0.5/0.75/1 倍速、循环、镜像和静音。"));
        TextView selected = label(selectedVideo == null ? "尚未选择视频" : "已选择：" + displayName(selectedVideo),
            14, selectedVideo == null ? Color.rgb(145, 148, 156) : Color.rgb(103, 190, 141), false);
        selected.setPadding(0, dp(16), 0, dp(4));
        parent.addView(selected);
        Button choose = secondaryButton(selectedVideo == null ? "选择参考视频" : "重新选择视频");
        choose.setOnClickListener(v -> videoPicker.launch(new String[]{"video/*"}));
        parent.addView(choose, actionParams());
        Button start = primaryButton("开始视频跟拍");
        start.setAlpha(selectedVideo == null ? 0.45f : 1f);
        start.setOnClickListener(v -> {
            if (selectedVideo == null) Toast.makeText(this, "请先选择参考视频", Toast.LENGTH_SHORT).show();
            else ensureOverlayPermission("video");
        });
        parent.addView(start, actionParams());
    }

    private void buildGalleryConfig(LinearLayout parent) {
        parent.addView(label("图集跟拍", 25, Color.WHITE, true));
        parent.addView(description("一次选择最多 50 张样片或分镜。悬浮窗中可以逐张切换并标记完成，适合姿势参考、探店镜头清单和商品标准图。"));
        String state = selectedImages.isEmpty() ? "尚未选择图片" : "已选择 " + selectedImages.size() + " 张 · 拍摄时可逐张打勾";
        TextView selected = label(state, 14, selectedImages.isEmpty() ? Color.rgb(145, 148, 156) : Color.rgb(103, 190, 141), false);
        selected.setPadding(0, dp(16), 0, dp(4));
        parent.addView(selected);
        Button choose = secondaryButton(selectedImages.isEmpty() ? "选择参考图集" : "重新选择图集");
        choose.setOnClickListener(v -> galleryPicker.launch(new String[]{"image/*"}));
        parent.addView(choose, actionParams());
        Button start = primaryButton("开始图集跟拍");
        start.setAlpha(selectedImages.isEmpty() ? 0.45f : 1f);
        start.setOnClickListener(v -> {
            if (selectedImages.isEmpty()) Toast.makeText(this, "请先选择参考图片", Toast.LENGTH_SHORT).show();
            else ensureOverlayPermission("gallery");
        });
        parent.addView(start, actionParams());
    }

    private void preparePromptOverlay() {
        draftScript = scriptEditor.getText().toString().trim();
        if (draftScript.isEmpty()) {
            Toast.makeText(this, "请先输入提词文稿", Toast.LENGTH_SHORT).show();
            return;
        }
        if (voiceFollowEnabled && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO);
            return;
        }
        ensureOverlayPermission("prompt");
    }

    private void ensureOverlayPermission(String mode) {
        pendingOverlayMode = mode;
        if (Settings.canDrawOverlays(this)) {
            pendingOverlayMode = null;
            startSelectedOverlay(mode);
            return;
        }
        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }

    private void startSelectedOverlay(String mode) {
        Intent service;
        if (mode.equals("prompt")) {
            getSharedPreferences("floating", MODE_PRIVATE).edit()
                .putString("prompt", draftScript)
                .putBoolean("voice_follow", voiceFollowEnabled)
                .putInt("scroll_speed", promptSpeed)
                .apply();
            service = new Intent(this, PromptOverlayService.class)
                .putExtra(PromptOverlayService.EXTRA_PROMPT, draftScript)
                .putExtra(PromptOverlayService.EXTRA_VOICE_FOLLOW, voiceFollowEnabled)
                .putExtra(PromptOverlayService.EXTRA_SCROLL_SPEED, promptSpeed);
        } else {
            service = new Intent(this, FloatingMediaOverlayService.class);
            if (mode.equals("video")) {
                service.setAction(FloatingMediaOverlayService.ACTION_VIDEO)
                    .putExtra(FloatingMediaOverlayService.EXTRA_VIDEO_URI, selectedVideo.toString());
            } else {
                ArrayList<String> values = new ArrayList<>();
                for (Uri uri : selectedImages) values.add(uri.toString());
                service.setAction(FloatingMediaOverlayService.ACTION_GALLERY)
                    .putStringArrayListExtra(FloatingMediaOverlayService.EXTRA_IMAGE_URIS, values);
            }
        }
        ContextCompat.startForegroundService(this, service);
        Toast.makeText(this, "悬浮工具已开启，现在可以切到拍摄 App", Toast.LENGTH_LONG).show();
        moveTaskToBack(true);
    }

    private void startScreenCaptureRequest() {
        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        screenCaptureLauncher.launch(manager.createScreenCaptureIntent());
    }

    private void persistReadPermission(Uri uri) {
        try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
        catch (SecurityException ignored) {}
    }

    private String displayName(Uri uri) {
        if (uri == null) return "";
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        } catch (RuntimeException ignored) {}
        String tail = uri.getLastPathSegment();
        return tail == null ? "参考素材" : tail;
    }

    private LinearLayout chipRow(String... values) {
        LinearLayout row = new LinearLayout(this);
        row.setPadding(0, dp(14), 0, 0);
        for (String value : values) {
            TextView chip = label(value, 11, Color.rgb(180, 184, 192), false);
            chip.setGravity(Gravity.CENTER);
            chip.setBackground(rounded(Color.rgb(37, 38, 43), 12, 0, 0));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(34), 1f);
            params.setMargins(dp(2), 0, dp(2), 0);
            row.addView(chip, params);
        }
        return row;
    }

    private TextView description(String value) {
        TextView view = label(value, 14, Color.rgb(158, 161, 170), false);
        view.setLineSpacing(dp(3), 1f);
        view.setPadding(0, dp(7), 0, 0);
        return view;
    }

    private Button primaryButton(String text) { return button(text, Color.rgb(54, 92, 235)); }
    private Button secondaryButton(String text) { return button(text, Color.rgb(47, 48, 54)); }

    private LinearLayout.LayoutParams actionParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56));
        params.topMargin = dp(12);
        return params;
    }

    private Button button(String text, int color) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(15);
        button.setAllCaps(false);
        button.setBackground(rounded(color, 16, 0, 0));
        return button;
    }

    private TextView label(String text, int sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(sp);
        view.setTextColor(color);
        if (bold) view.setTypeface(null, android.graphics.Typeface.BOLD);
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
