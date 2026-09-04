package com.baiyalab.cocamera;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;

public final class HomeActivity extends AppCompatActivity {
    private static final class Scene {
        final String title;
        final String subtitle;
        final String mode;
        final int accent;

        Scene(String title, String subtitle, String mode, int accent) {
            this.title = title;
            this.subtitle = subtitle;
            this.mode = mode;
            this.accent = accent;
        }
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        buildUi();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.BLACK);
        scroll.setFillViewport(true);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(18), dp(22), dp(18), dp(28));
        scroll.addView(page);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand = label("镜界创作", 18, Color.WHITE, true);
        TextView start = label("开始", 34, Color.WHITE, true);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(brand);
        titles.addView(start);
        header.addView(titles, new LinearLayout.LayoutParams(0, dp(92), 1f));
        Button works = button("作品库", Color.rgb(32, 32, 34));
        works.setOnClickListener(v -> openGallery());
        header.addView(works, new LinearLayout.LayoutParams(dp(92), dp(48)));
        page.addView(header);

        LinearLayout floating = new LinearLayout(this);
        floating.setOrientation(LinearLayout.VERTICAL);
        floating.setPadding(dp(20), dp(20), dp(20), dp(20));
        floating.setBackground(rounded(Color.rgb(20, 28, 36), 24, 2, Color.rgb(20, 139, 255)));
        floating.addView(label("悬浮创作  ›", 25, Color.WHITE, true));
        floating.addView(label("录屏讲解 · 悬浮提词 · 视频跟拍 · 图集跟拍", 14, Color.rgb(174, 178, 184), false));
        floating.setOnClickListener(v -> startActivity(new Intent(this, FloatingHubActivity.class)));
        LinearLayout.LayoutParams floatingParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(118));
        floatingParams.topMargin = dp(6);
        floatingParams.bottomMargin = dp(26);
        page.addView(floating, floatingParams);

        page.addView(label("拍摄场景", 24, Color.WHITE, true));
        page.addView(label("按用途一键准备，也可以快速拍摄后自由组合", 14, Color.rgb(145, 145, 150), false));

        List<Scene> scenes = new ArrayList<>();
        scenes.add(new Scene("运动·旅行双摄", "现场主画面 + 可拖动人像小窗", "travel", Color.rgb(52, 116, 190)));
        scenes.add(new Scene("预录视频", "保留按下前 15 秒", "prerecord", Color.rgb(129, 94, 43)));
        scenes.add(new Scene("提词拍摄", "自动滚动流畅口播", "prompt", Color.rgb(85, 70, 190)));
        scenes.add(new Scene("现场讲解", "时间地点方位水印", "watermark", Color.rgb(37, 126, 93)));
        scenes.add(new Scene("探店测评", "地名水印 + 口播提纲", "review", Color.rgb(154, 73, 55)));
        scenes.add(new Scene("开箱展示", "俯拍构图网格 + 讲解", "unboxing", Color.rgb(118, 79, 42)));
        scenes.add(new Scene("访谈对话", "左右同框 + 采访提纲", "interview", Color.rgb(79, 85, 105)));

        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < scenes.size(); i += 2) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.addView(sceneCard(scenes.get(i)), weightedCardParams(true));
            if (i + 1 < scenes.size()) row.addView(sceneCard(scenes.get(i + 1)), weightedCardParams(false));
            grid.addView(row, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(140)));
        }
        LinearLayout.LayoutParams gridParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        gridParams.topMargin = dp(14);
        page.addView(grid, gridParams);

        Button quick = button("📷  快速拍摄", Color.rgb(43, 43, 45));
        quick.setTextSize(20);
        quick.setOnClickListener(v -> openCamera("single"));
        LinearLayout.LayoutParams quickParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(68));
        quickParams.topMargin = dp(10);
        page.addView(quick, quickParams);

        setContentView(scroll);
    }

    private View sceneCard(Scene scene) {
        FrameLayout card = new FrameLayout(this);
        card.setBackground(rounded(Color.rgb(22, 22, 24), 22, 0, 0));
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.BOTTOM);
        content.setPadding(dp(16), dp(14), dp(16), dp(16));
        TextView dot = label("●", 18, scene.accent, true);
        content.addView(dot, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, 0, 1f));
        content.addView(label(scene.title, 20, Color.WHITE, true));
        content.addView(label(scene.subtitle, 12, Color.rgb(150, 150, 155), false));
        card.addView(content, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        card.setOnClickListener(v -> openCamera(scene.mode, scene.title));
        return card;
    }

    private LinearLayout.LayoutParams weightedCardParams(boolean first) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(128), 1f);
        params.topMargin = dp(6);
        params.bottomMargin = dp(6);
        if (first) params.rightMargin = dp(6); else params.leftMargin = dp(6);
        return params;
    }

    private void openCamera(String mode) {
        openCamera(mode, "快速拍摄");
    }

    private void openCamera(String mode, String title) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra("scene_mode", mode);
        intent.putExtra("scene_title", title);
        startActivity(intent);
    }

    private void openGallery() {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video/*");
        try { startActivity(intent); } catch (Exception ignored) { openCamera("single"); }
    }

    private Button button(String text, int color) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setBackground(rounded(color, 22, 0, 0));
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
