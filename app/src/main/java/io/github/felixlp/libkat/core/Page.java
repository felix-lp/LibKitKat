package io.github.felixlp.libkat.core;

import org.json.JSONObject;

/** Страница главы. Размеры известны заранее — ленту можно разметить до загрузки картинок. */
public class Page {
    public int index;
    /** Путь относительно сервера изображений, начинается с "//". */
    public String url;
    public int width;
    public int height;

    public static Page parse(JSONObject o) {
        Page p = new Page();
        p.index = o.optInt("slug");
        p.url = o.optString("url");
        p.width = o.optInt("width");
        p.height = o.optInt("height");
        return p;
    }

    /** Отношение высоты к ширине; 1.42 — типичная страница, если размеры неизвестны. */
    public float ratio() {
        return width > 0 && height > 0 ? (float) height / width : 1.42f;
    }
}
