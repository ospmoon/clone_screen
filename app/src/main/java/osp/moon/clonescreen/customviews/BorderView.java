package osp.moon.clonescreen.customviews;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class BorderView extends View {
    private Paint borderPaint;
    private RectF borderRect;
    private float borderWidthPx = 5f;

    public BorderView(Context context) {
        super(context);
        init();
    }

    public BorderView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public BorderView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        borderPaint = new Paint();
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(borderWidthPx);
        borderPaint.setColor(Color.GREEN); // Начальный цвет
        borderPaint.setAntiAlias(true);
        borderRect = new RectF();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float halfStroke = borderWidthPx / 2;
        borderRect.set(halfStroke, halfStroke, w - halfStroke, h - halfStroke);
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawRect(borderRect, borderPaint);
    }

    public void setBorderColor(int color) {
        borderPaint.setColor(color);
        invalidate();
    }

    public void setBorderWidth(float pixels) {
        borderWidthPx = pixels;
        borderPaint.setStrokeWidth(borderWidthPx);
        // Обновляем RectF при изменении толщины, если размеры уже известны
        if (getWidth() > 0 && getHeight() > 0) {
            float halfStroke = borderWidthPx / 2;
            borderRect.set(halfStroke, halfStroke, getWidth() - halfStroke, getHeight() - halfStroke);
        }
        invalidate();
    }
}

