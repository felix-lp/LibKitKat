package io.github.felixlp.libkat;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

/**
 * Одна страница во весь экран: вписана целиком, щипок и двойной тап — зум, перетаскивание — панорама.
 * Без зума свайп и тап по краю листают; направление (слева направо или справа налево) задаёт rtl.
 */
public class PagedView extends View implements ImageLoader.Target {
    private static final float MAX_ZOOM = 5f;
    private static final float DOUBLE_TAP_ZOOM = 2.5f;

    public interface Listener {
        /** @param delta +1 — следующая страница по порядку чтения, -1 — предыдущая */
        void onTurn(int delta);

        void onCenterTap();

        void onRetry();
    }

    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF dst = new RectF();
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestures;

    private Listener listener;
    private boolean rtl;
    private String key;
    private int number;
    private ImageLoader.Decoded image;
    private String status;
    private boolean failed;

    /** Зум поверх вписывания (1 — страница целиком) и сдвиг от центра в пикселях экрана. */
    private float zoom = 1f;
    private float tx;
    private float ty;

    public PagedView(Context context) {
        super(context);
        textPaint.setColor(Color.GRAY);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(18 * getResources().getDisplayMetrics().scaledDensity);

        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                zoomAround(zoom * d.getScaleFactor(), d.getFocusX(), d.getFocusY());
                return true;
            }
        });
        gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (!zoomed()) return false;
                tx -= dx;
                ty -= dy;
                clamp();
                invalidate();
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (zoomed() || scaleDetector.isInProgress()) return false;
                float min = 600 * getResources().getDisplayMetrics().density;
                if (Math.abs(vx) < min || Math.abs(vx) < Math.abs(vy)) return false;
                // Свайп влево открывает то, что лежит справа
                turnTowards(vx < 0 ? 1 : -1);
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                zoomAround(zoomed() ? 1f : DOUBLE_TAP_ZOOM, e.getX(), e.getY());
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (listener == null) return true;
                if (failed) {
                    listener.onRetry();
                    return true;
                }
                float w = getWidth();
                if (e.getX() < w * 0.3f) turnTowards(-1);
                else if (e.getX() > w * 0.7f) turnTowards(1);
                else listener.onCenterTap();
                return true;
            }
        });
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setRtl(boolean rtl) {
        this.rtl = rtl;
    }

    /** @param side +1 — правая сторона экрана, -1 — левая */
    private void turnTowards(int side) {
        if (listener != null) listener.onTurn(rtl ? -side : side);
    }

    public void bind(String key, int number) {
        if (key.equals(this.key) && image != null) return;
        this.key = key;
        this.number = number;
        image = null;
        failed = false;
        status = "Загрузка…";
        zoom = 1f;
        tx = 0;
        ty = 0;
        invalidate();
    }

    @Override
    public void onImage(String key, ImageLoader.Decoded d) {
        if (!key.equals(this.key)) return;
        image = d;
        failed = false;
        status = null;
        invalidate();
    }

    @Override
    public void onImageError(String key, String message) {
        if (!key.equals(this.key)) return;
        failed = true;
        status = message + "\nНажмите, чтобы повторить";
        invalidate();
    }

    private boolean zoomed() {
        return zoom > 1.01f;
    }

    /** Масштаб вписывания: страница целиком на экране. */
    private float fit() {
        if (image == null || getWidth() == 0) return 1f;
        return Math.min(getWidth() / (float) image.width, getHeight() / (float) image.height);
    }

    private void zoomAround(float newZoom, float fx, float fy) {
        if (image == null) return;
        newZoom = Math.max(1f, Math.min(MAX_ZOOM, newZoom));
        // Точка под пальцем остаётся на месте
        float cx = fx - getWidth() / 2f;
        float cy = fy - getHeight() / 2f;
        float k = newZoom / zoom;
        tx = cx - (cx - tx) * k;
        ty = cy - (cy - ty) * k;
        zoom = newZoom;
        clamp();
        invalidate();
    }

    private void clamp() {
        if (image == null) return;
        float s = fit() * zoom;
        float dw = image.width * s;
        float dh = image.height * s;
        float mx = Math.max(0, (dw - getWidth()) / 2f);
        float my = Math.max(0, (dh - getHeight()) / 2f);
        tx = Math.max(-mx, Math.min(mx, tx));
        ty = Math.max(-my, Math.min(my, ty));
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        gestures.onTouchEvent(event);
        return true;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        clamp();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (image == null) {
            float y = getHeight() / 2f;
            if (number > 0) canvas.drawText(String.valueOf(number), getWidth() / 2f, y, textPaint);
            if (status != null) {
                float line = textPaint.getTextSize() * 1.4f;
                for (String s : status.split("\n")) {
                    y += line;
                    canvas.drawText(s, getWidth() / 2f, y, textPaint);
                }
            }
            return;
        }
        float s = fit() * zoom;
        float dw = image.width * s;
        float left = (getWidth() - dw) / 2f + tx;
        float top = (getHeight() - image.height * s) / 2f + ty;
        for (Bitmap t : image.tiles) {
            float h = t.getHeight() * s;
            dst.set(left, top, left + dw, top + h);
            canvas.drawBitmap(t, null, dst, bitmapPaint);
            top += h;
        }
    }
}
