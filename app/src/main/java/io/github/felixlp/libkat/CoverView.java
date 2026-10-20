package io.github.felixlp.libkat;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.ImageView;

/** Обложка с пропорциями 1:1.42, декодируется под фактическую ширину на экране. */
public class CoverView extends ImageView implements ImageLoader.Target {
    /** Шире полная обложка на сервере не бывает (375 px), больше декодировать незачем. */
    private static final int MAX_DECODE_WIDTH = 400;

    private String url;
    private String referer;
    private String key;

    public CoverView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setScaleType(ScaleType.CENTER_CROP);
        setBackgroundColor(0xFF2A2A2A);
    }

    public void load(String url, String referer) {
        if (url != null && url.equals(this.url) && key != null) return;
        this.url = url;
        this.referer = referer;
        key = null;
        setImageDrawable(null);
        // До разметки ширина неизвестна — тогда загрузку запустит onSizeChanged
        if (getWidth() > 0) start(getWidth());
    }

    private void start(int width) {
        if (url == null) return;
        int w = Math.min(width, MAX_DECODE_WIDTH);
        String k = ImageLoader.key(url, w);
        if (k.equals(key)) return;
        key = k;
        App.get().images.load(url, referer, w, this);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0 && w != oldw) start(w);
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
