package io.github.felixlp.libkat;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.ImageView;

/** Обложка с пропорциями 1:1.42. */
public class CoverView extends ImageView implements ImageLoader.Target {
    private static final int DECODE_WIDTH = 240;

    private String key;

    public CoverView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setScaleType(ScaleType.CENTER_CROP);
        setBackgroundColor(0xFF2A2A2A);
    }

    public void load(String url, String referer) {
        if (url == null) {
            key = null;
            setImageDrawable(null);
            return;
        }
        String k = ImageLoader.key(url, DECODE_WIDTH);
        if (k.equals(key)) return;
        key = k;
        setImageDrawable(null);
        App.get().images.load(url, referer, DECODE_WIDTH, this);
    }

    @Override
    public void onImage(String key, ImageLoader.Decoded image) {
        if (key.equals(this.key)) setImageBitmap(image.tiles[0]);
    }

    @Override
    public void onImageError(String key, String message) {
        // Пустая обложка — не повод для сообщения
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }
        setMeasuredDimension(w, Math.round(w * 1.42f));
    }
}
