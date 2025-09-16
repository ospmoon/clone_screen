package osp.moon.clonescreen; // Убедитесь, что пакет правильный

import android.content.Context;
import android.util.AttributeSet;    import android.view.SurfaceView;
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
        if (width < 0 || height < 0) throw new IllegalArgumentException("Size cannot be negative.");
        if (ratioWidth == width && ratioHeight == height) return;
        ratioWidth = width;
        ratioHeight = height;
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        int width = View.MeasureSpec.getSize(widthMeasureSpec);
        int height = View.MeasureSpec.getSize(heightMeasureSpec);
        if (0 == ratioWidth || 0 == ratioHeight) {
            setMeasuredDimension(width, height);
        } else {
            if (width < height * ratioWidth / (float) ratioHeight) {
                setMeasuredDimension(width, (int) (width * ratioHeight / (float) ratioWidth));
            } else {
                setMeasuredDimension((int) (height * ratioWidth / (float) ratioHeight), height);
            }
        }
    }
}
    