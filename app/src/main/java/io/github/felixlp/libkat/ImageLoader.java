package io.github.felixlp.libkat;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Rect;
import android.util.LruCache;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.felixlp.libkat.core.ApiException;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Загрузка картинок: диск → память. Страницы вебтунов бывают 720×15000 —
 * такие режем на тайлы через BitmapRegionDecoder, чтобы не упереться в лимит текстуры и память.
 */
public class ImageLoader {
    /** Максимальная высота одного тайла в пикселях (безопасно для GPU 2013 года). */
    private static final int MAX_TILE = 2048;
    /** До такого размера (обложки) декодируем в ARGB_8888. */
    private static final long FULL_COLOR_PIXELS = 400L * 600;

    public static final class Decoded {
        public final Bitmap[] tiles;
        public final int width;
        public final int height;
        final int bytes;

        Decoded(Bitmap[] tiles) {
            this.tiles = tiles;
            int h = 0;
            int b = 0;
            for (Bitmap t : tiles) {
                h += t.getHeight();
                b += t.getRowBytes() * t.getHeight();
            }
            this.width = tiles[0].getWidth();
            this.height = h;
            this.bytes = b;
        }
    }

    public interface Target {
        void onImage(String key, Decoded image);

        void onImageError(String key, String message);
    }

    private final LruCache<String, Decoded> memory;
    private final DiskCache disk;
    private final ExecutorService network = Executors.newFixedThreadPool(3);
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    /** Ожидающие цели по ключу; трогается только из главного потока. */
    private final Map<String, List<Target>> pending = new HashMap<>();
    private final Set<String> prefetching = Collections.synchronizedSet(new HashSet<String>());

    ImageLoader(Context context) {
        int kb = (int) (Runtime.getRuntime().maxMemory() / 1024);
        memory = new LruCache<String, Decoded>(kb / 4) {
            @Override
            protected int sizeOf(String key, Decoded value) {
                return value.bytes / 1024;
            }
        };
        disk = new DiskCache(new File(context.getCacheDir(), "img"), 300L * 1024 * 1024);
        network.execute(new Runnable() {
            @Override
            public void run() {
                disk.trim();
            }
        });
    }

    public static String key(String url, int width) {
        return width + "|" + url;
    }

    /** Вызывать из главного потока. Цель получает ответ с ключом — по нему она проверяет, что ещё ждёт эту картинку. */
    public void load(final String url, final String referer, final int width, Target target) {
        final String key = key(url, width);
        Decoded d = memory.get(key);
        if (d != null) {
            target.onImage(key, d);
            return;
        }
        List<Target> waiting = pending.get(key);
        if (waiting != null) {
            if (!waiting.contains(target)) waiting.add(target);
            return;
        }
        waiting = new ArrayList<>();
        waiting.add(target);
        pending.put(key, waiting);
        network.execute(new Runnable() {
            @Override
            public void run() {
                final File file;
                try {
                    file = fetch(url, referer);
                } catch (Exception e) {
                    deliver(key, null, Bg.message(e));
                    return;
                }
                decoder.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            deliver(key, decode(file, width), null);
                        } catch (Exception e) {
                            disk.remove(url);
                            deliver(key, null, Bg.message(e));
                        }
                    }
                });
            }
        });
    }

    /** Только скачать на диск — для следующих страниц главы. */
    public void prefetch(final String url, final String referer) {
        if (disk.get(url) != null || !prefetching.add(url)) return;
        network.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    fetch(url, referer);
                } catch (Exception ignored) {
                    // Повторим при показе
                } finally {
                    prefetching.remove(url);
                }
            }
        });
    }

    public long diskSize() {
        return disk.size();
    }

    public void clear() {
        memory.evictAll();
        disk.clear();
    }

    private void deliver(final String key, final Decoded image, final String error) {
        Bg.MAIN.post(new Runnable() {
            @Override
            public void run() {
                if (image != null) memory.put(key, image);
                List<Target> waiting = pending.remove(key);
                if (waiting == null) return;
                for (Target t : waiting) {
                    if (image != null) t.onImage(key, image);
                    else t.onImageError(key, error);
                }
            }
        });
    }

    private File fetch(String url, String referer) throws IOException {
        File f = disk.get(url);
        if (f != null) return f;
        Request.Builder rb = new Request.Builder().url(url)
                .header("Accept", "image/webp,image/jpeg,image/png,image/*;q=0.8");
        if (referer != null) rb.header("Referer", referer);
        try (Response r = App.get().mediaClient().newCall(rb.build()).execute()) {
            if (!r.isSuccessful()) throw new ApiException(r.code(), "Картинка: HTTP " + r.code());
            String type = r.header("Content-Type", "");
            if (type.startsWith("text/")) throw new ApiException(r.code(), "Вместо картинки пришла страница (блокировка?)");
            return disk.put(url, r.body().byteStream());
        }
    }

    private Decoded decode(File file, int width) throws IOException {
        try {
            return decode(file, width, 1);
        } catch (OutOfMemoryError e) {
            memory.evictAll();
            try {
                // Вторая попытка вдвое мельче — лучше мыло, чем падение
                return decode(file, width, 2);
            } catch (OutOfMemoryError e2) {
                throw new IOException("Не хватило памяти на картинку");
            }
        }
    }

    private static Decoded decode(File file, int width, int extraSample) throws IOException {
        String path = file.getPath();
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("Неизвестный формат картинки");

        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= width) sample *= 2;
        sample *= extraSample;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        // Обложкам — полный цвет (в 565 на градиентах полосы), страницам — 565 ради памяти
        long pixels = (long) (bounds.outWidth / sample) * (bounds.outHeight / sample);
        o.inPreferredConfig = pixels <= FULL_COLOR_PIXELS ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
        o.inDither = true;

        if (bounds.outHeight / sample <= MAX_TILE) {
            Bitmap b = BitmapFactory.decodeFile(path, o);
            if (b == null) throw new IOException("Картинка не декодировалась");
            return new Decoded(new Bitmap[]{b});
        }
        BitmapRegionDecoder rd = BitmapRegionDecoder.newInstance(path, false);
        try {
            List<Bitmap> tiles = new ArrayList<>();
            int step = MAX_TILE * sample;
            for (int y = 0; y < bounds.outHeight; y += step) {
                Rect r = new Rect(0, y, bounds.outWidth, Math.min(bounds.outHeight, y + step));
                Bitmap t = rd.decodeRegion(r, o);
                if (t == null) throw new IOException("Не удалось нарезать длинную страницу");
                tiles.add(t);
            }
            return new Decoded(tiles.toArray(new Bitmap[0]));
        } finally {
            rd.recycle();
        }
    }
}
