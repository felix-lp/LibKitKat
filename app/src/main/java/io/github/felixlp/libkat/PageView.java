package io.github.felixlp.libkat;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Страница в ленте читалки: высота известна заранее из размеров, картинка — набор тайлов. */
public class PageView extends View implements ImageLoader.Target {
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF dst = new RectF();

    private float ratio = 1.42f;
    private String key;
    private ImageLoader.Decoded image;
    private String status;
    private boolean failed;
    private int number;

    public PageView(Context context) {
        super(context);
        textPaint.setColor(Color.GRAY);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(18 * getResources().getDisplayMetrics().scaledDensity);
    }

    public void bind(String key, int number, float ratio) {
        if (key.equals(this.key) && image != null) return;
        this.key = key;
        this.number = number;
        this.ratio = ratio;
        image = null;
        failed = false;
        status = "Загрузка…";
        requestLayout();
        invalidate();
    }

    public String key() {
        return key;
    }

    public boolean failed() {
        return failed;
    }

    @Override
    public void onImage(String key, ImageLoader.Decoded d) {
        if (!key.equals(this.key)) return;
        image = d;
        failed = false;
        status = null;
        float r = (float) d.height / d.width;
        if (Math.abs(r - ratio) > 0.01f) {
            ratio = r;
            requestLayout();
        }
        invalidate();
    }

    @Override
    public void onImageError(String key, String message) {
        if (!key.equals(this.key)) return;
        failed = true;
        status = message + "\nНажмите, чтобы повторить";
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        setMeasuredDimension(w, Math.max(1, Math.round(w * ratio)));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (image == null) {
            // Строка статуса держится у верха страницы — длинную страницу видно сразу, а не через экран
            float y = Math.min(getHeight() / 2f, 200 * getResources().getDisplayMetrics().density);
            canvas.drawText(String.valueOf(number), getWidth() / 2f, y, textPaint);
            if (status != null) {
                float line = textPaint.getTextSize() * 1.4f;
                for (String s : status.split("\n")) {
                    y += line;
                    canvas.drawText(s, getWidth() / 2f, y, textPaint);
                }
            }
            return;
        }
        float scale = getWidth() / (float) image.width;
        float y = 0;
        for (Bitmap t : image.tiles) {
            float h = t.getHeight() * scale;
            dst.set(0, y, getWidth(), y + h);
            canvas.drawBitmap(t, null, dst, bitmapPaint);
            y += h;
        }
    }
}
