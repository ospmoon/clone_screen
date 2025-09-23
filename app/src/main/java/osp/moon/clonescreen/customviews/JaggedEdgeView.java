package osp.moon.clonescreen.customviews;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

import osp.moon.clonescreen.R;

public class JaggedEdgeView extends FrameLayout {

    private Paint backgroundPaint;
    private Path clipPath;
    private float jaggedEdgeHeight = 40f; // Высота "зубцов"
    private int numberOfTeeth = 10;     // Количество "зубцов"
    private boolean flipShape = false;

    public JaggedEdgeView(Context context) {
        super(context);
        init(null);
    }
    public JaggedEdgeView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(attrs);
    }
    public JaggedEdgeView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(attrs);
    }
    private void init(AttributeSet attrs) {
        backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        if (attrs != null) {
            // Если вы определили кастомные атрибуты в attrs.xml:
            TypedArray a = getContext().getTheme().obtainStyledAttributes(
                    attrs,
                    R.styleable.JaggedEdgeView, // Используйте то же имя styleable или новое
                    0, 0);
            try {
                jaggedEdgeHeight = a.getDimension(R.styleable.JaggedEdgeView_jaggedEdgeHeight, 40f);
                numberOfTeeth = a.getInteger(R.styleable.JaggedEdgeView_numberOfTeeth, 10);
                int bgColor = a.getColor(R.styleable.JaggedEdgeView_viewBackgroundColor, Color.TRANSPARENT);
                backgroundPaint.setColor(bgColor);
                backgroundPaint.setStyle(Paint.Style.FILL);
                flipShape = a.getBoolean(R.styleable.JaggedEdgeView_flipShape, false);
            } finally {
                a.recycle();
            }
        } else {
            backgroundPaint.setColor(Color.RED); // Цвет фона View
            backgroundPaint.setStyle(Paint.Style.FILL);
        }
        clipPath = new Path();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateClipPath(w, h);
    }

    private void updateClipPath(int width, int height) {
        clipPath.reset();

        if (width == 0 || height == 0) { // Предотвращение деления на ноль или бессмысленных вычислений
            return;
        }
        if (!flipShape) {
            float actualContentHeight = height - jaggedEdgeHeight; // Высота "прямой" части View
            float toothWidth = (float) width / numberOfTeeth;

            clipPath.moveTo(0, 0); // Верхний левый угол
            clipPath.lineTo(width, 0); // Верхний правый угол
            clipPath.lineTo(width, actualContentHeight); // Правый край до начала зубцов

            // Рисуем зубцы снизу, двигаясь справа налево
            for (int i = 0; i < numberOfTeeth; i++) {
                // Начальная точка текущего основания зубца (справа)
                float currentBaseX = width - (i * toothWidth);
                // Вершина зубца (посередине основания)
                float toothTipX = currentBaseX - (toothWidth / 2);
                // Конечная точка текущего основания зубца (слева)
                float nextBaseX = width - ((i + 1) * toothWidth);
                // Если это нечетный зубец (i % 2 == 0, так как начинаем с 0), его вершина "вдавлена"
                // Если четный (i % 2 != 0), его вершина "выступает"
                // Чтобы получить эффект "оторванного листа", зубцы обычно идут вверх
                float toothTipY = (i % 2 == 0) ? actualContentHeight + jaggedEdgeHeight : actualContentHeight;
                // Для классического зигзага, где все вершины на одной высоте:
                // float toothTipY = actualContentHeight + jaggedEdgeHeight;

                // Рисуем одну сторону зубца
                clipPath.lineTo(toothTipX, toothTipY);
                // Рисуем другую сторону зубца, возвращаясь к основанию
                clipPath.lineTo(nextBaseX, actualContentHeight);
            }

            clipPath.lineTo(0, actualContentHeight); // Левый край до начала зубцов
            clipPath.close(); // амыкаем путь (соединяем с первой точкой 0,0)
        } else {
            // jaggedEdgeHeight теперь будет определять, насколько "вверх" идут зубцы от новой "верхней" прямой линии
            // actualContentHeight теперь будет представлять Y-координату "нижней" прямой линии после отзеркаливания.
            // Если раньше actualContentHeight было height - jaggedEdgeHeight (линия ВНИЗУ),
            // то после отзеркаливания эта линия окажется вверху, на Y = jaggedEdgeHeight.
            float mirroredBaseLineY = jaggedEdgeHeight; // Это будет новая "верхняя" прямая линия
            float toothTipDepth = jaggedEdgeHeight; // Глубина зубцов, насколько они "опускаются" от mirroredBaseLineY

            float toothWidth = (float) width / numberOfTeeth;

            // --- Начало отзеркаленного пути ---

            // 1. Было: clipPath.moveTo(10, 30); -> Верхний левый угол (оригинал)
            //    Стало: Новая начальная точка внизу слева
            clipPath.moveTo(0, height); // Отзеркаленная Y

            // 2. Было: clipPath.lineTo(width-33, 0); -> Верхний правый угол (оригинал)
            //    Стало: Новая точка внизу справа
            clipPath.lineTo(width , height); // height - 0 это просто height
            // 3. Было: clipPath.lineTo(width, actualContentHeight); -> Правый край до начала зубцов (оригинал)
            //    actualContentHeight = (старая_высота_view - jaggedEdgeHeight_старый)
            //    Стало: Правый край до начала зубцов (теперь зубцы будут вверху).
            //           Линия, от которой начинаются зубцы, теперь mirroredBaseLineY.
            clipPath.lineTo(width, mirroredBaseLineY);


            // 4. Рисуем зубцы сверху, двигаясь справа налево
            for (int i = 0; i < numberOfTeeth; i++) {
                float currentBaseX = width - (i * toothWidth);
                float toothTipX = currentBaseX - (toothWidth / 2);
                float nextBaseX = width - ((i + 1) * toothWidth);

                // Оригинальная логика для Y зубца:
                // float originalToothTipY = (i % 2 == 0) ? actualContentHeight + jaggedEdgeHeight : actualContentHeight;
                // Теперь зубцы "свисают" вниз от mirroredBaseLineY.
                // mirroredBaseLineY - это как бы "потолок" для основания зубцов.
                // toothTipDepth - глубина, на которую опускается вершина зубца.

                float mirroredToothTipY;
                if (i % 2 == 0) { // Вершина "вдавлена" (в оригинале шла вверх, теперь пойдет вниз дальше)
                    mirroredToothTipY = mirroredBaseLineY - toothTipDepth; // Опускаем вершину ниже базовой линии
                } else { // Вершина "выступает" (в оригинале была на уровне actualContentHeight, теперь на mirroredBaseLineY)
                    mirroredToothTipY = mirroredBaseLineY;
                }

                // Рисуем одну сторону зубца
                clipPath.lineTo(toothTipX, mirroredToothTipY);
                // Рисуем другую сторону зубца, возвращаясь к "потолку" зубцов
                clipPath.lineTo(nextBaseX, mirroredBaseLineY);
            }
            // 5. Было: clipPath.lineTo(0, actualContentHeight); (Левый край до начала зубцов в оригинале)
            //    Стало: Левый край "прямой" части, которая теперь внизу.
            //           Её Y-координата также upperBaseLineY.
            clipPath.lineTo(0, mirroredBaseLineY);

            // 6. Было: clipPath.close(); // Соединяло с (0,0) или с (10,30) если это была первая точка
            //    Стало: Замыкаем путь, соединяя с первой отзеркаленной точкой (10, height - 30f)
            clipPath.close();
        }
    }
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (clipPath.isEmpty()) { // Если путь не был создан (например, onSizeChanged еще не вызывался)
            return;
        }

        // 1. Обрезаем канву по нашему пути
        canvas.clipPath(clipPath);

        // 2. Рисуем фон (или любое другое содержимое View)
        // Цвет фона уже установлен в backgroundPaint
        canvas.drawRect(0, 0, getWidth(), getHeight(), backgroundPaint);

        // Если бы у вас были другие элементы для отрисовки внутри этого View,
        // вы бы рисовали их здесь, и они бы автоматически обрезались по clipPath.
        // Например:
        // textPaint.setColor(Color.BLACK);
        // canvas.drawText("Hello Jagged World!", getWidth() / 2f, getHeight() / 2f, textPaint);
    }

    // Методы для настройки параметров "зубцов" из кода (опционально)
    public void setJaggedEdgeHeight(float jaggedEdgeHeight) {
        this.jaggedEdgeHeight = jaggedEdgeHeight;
        updateClipPath(getWidth(), getHeight()); // Обновляем путь
        invalidate(); // Перерисовываем View
    }

    public float getJaggedEdgeHeight() {
        return jaggedEdgeHeight;
    }

    public void setNumberOfTeeth(int numberOfTeeth) {
        if (numberOfTeeth <= 0) {
            this.numberOfTeeth = 1; // Минимум один зубец
        } else {
            this.numberOfTeeth = numberOfTeeth;
        }
        updateClipPath(getWidth(), getHeight());
        invalidate();
    }

    public int getNumberOfTeeth() {
        return numberOfTeeth;
    }

    public void setViewBackgroundColor(int color) {
        backgroundPaint.setColor(color);
        invalidate();
    }
}
