package com.baiyalab.cocamera;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Screen-edge soft light for cameras without a hardware front flash. */
public final class ScreenLightOverlayView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int mode;

    public ScreenLightOverlayView(Context context) {
        super(context);
        setClickable(false);
        setFocusable(false);
        setVisibility(GONE);
    }

    public void setMode(int mode) {
        this.mode = mode;
        setVisibility(mode == 0 ? GONE : VISIBLE);
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int color = mode == 2 ? Color.rgb(255, 225, 185)
            : mode == 3 ? Color.rgb(214, 235, 255) : Color.WHITE;
        float density = getResources().getDisplayMetrics().density;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(27f * density);
        paint.setColor(color);
        paint.setAlpha(225);
        float inset = paint.getStrokeWidth() / 2f;
        canvas.drawRoundRect(new RectF(inset, inset, getWidth() - inset, getHeight() - inset),
            26f * density, 26f * density, paint);

        paint.setStrokeWidth(48f * density);
        paint.setAlpha(52);
        inset = paint.getStrokeWidth() / 2f;
        canvas.drawRoundRect(new RectF(inset, inset, getWidth() - inset, getHeight() - inset),
            34f * density, 34f * density, paint);
    }
}
