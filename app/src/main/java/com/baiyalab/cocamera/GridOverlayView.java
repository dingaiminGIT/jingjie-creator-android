package com.baiyalab.cocamera;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

public final class GridOverlayView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public GridOverlayView(Context context) {
        super(context);
        paint.setColor(Color.argb(105, 255, 255, 255));
        paint.setStrokeWidth(getResources().getDisplayMetrics().density);
        setClickable(false);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        canvas.drawLine(w / 3f, 0, w / 3f, h, paint);
        canvas.drawLine(2f * w / 3f, 0, 2f * w / 3f, h, paint);
        canvas.drawLine(0, h / 3f, w, h / 3f, paint);
        canvas.drawLine(0, 2f * h / 3f, w, 2f * h / 3f, paint);
    }
}
