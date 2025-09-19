package osp.moon.clonescreen.customviews;

import android.content.Context;
import android.util.AttributeSet;
import android.view.SurfaceView;
import android.view.View;

public class AutoFitSurfaceView extends SurfaceView {
    private int ratioWidth = 0;
    private int ratioHeight = 0;

    public AutoFitSurfaceView(Context context) { this(context, null); }
    public AutoFitSurfaceView(Context context, AttributeSet attrs) { this(context, attrs, 0); }
    public AutoFitSurfaceView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    public void setAspectRatio(int width, int height) {
        if (width < 0 || height < 0) {
            throw new IllegalArgumentException("Size cannot be negative.");
        }
        ratioWidth = width;
        ratioHeight = height;
        // --- ИЗМЕНЕНИЕ: Мы будем управлять размерами напрямую ---
        // Вместо requestLayout() мы просто сохраняем пропорции для будущего onMeasure.
        // Главное изменение будет в onMeasure.
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        // Получаем полный размер родительского контейнера
        int parentWidth = View.MeasureSpec.getSize(widthMeasureSpec);
        int parentHeight = View.MeasureSpec.getSize(heightMeasureSpec);

        if (ratioWidth == 0 || ratioHeight == 0) {
            // Если пропорции не заданы, занимаем все место
            setMeasuredDimension(parentWidth, parentHeight);
            return;
        }

        // --- НОВАЯ, БОЛЕЕ НАДЕЖНАЯ ЛОГИКА ---
        // Цель: вписать прямоугольник с нужными пропорциями (ratioWidth/ratioHeight)
        // в родительский прямоугольник (parentWidth/parentHeight), сохраняя пропорции.

        float videoAspectRatio = (float) ratioWidth / ratioHeight;
        float parentAspectRatio = (float) parentWidth / parentHeight;

        int finalWidth;
        int finalHeight;

        if (videoAspectRatio > parentAspectRatio) {
            // Видео шире, чем родитель. Ширина равна родительской.
            finalWidth = parentWidth;
            finalHeight = (int) (finalWidth / videoAspectRatio);
        } else {
            // Видео выше, чем родитель. Высота равна родительской.
            finalHeight = parentHeight;
            finalWidth = (int) (finalHeight * videoAspectRatio);
        }

        setMeasuredDimension(finalWidth, finalHeight);
    }
}
