package io.github.felixlp.libkat.core;

import org.json.JSONObject;

/** Закладка тайтла: в какой папке он лежит. */
public class Bookmark {
    public int id;
    /** id папки. */
    public int status;

    static Bookmark parse(JSONObject o) {
        if (o == null || o.optInt("id") == 0) return null;
        Bookmark b = new Bookmark();
        b.id = o.optInt("id");
        b.status = o.optInt("status");
        return b;
    }
}
