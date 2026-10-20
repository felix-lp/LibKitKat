package io.github.felixlp.libkat.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Тайтл: манга или аниме. Поля деталей заполнены только после LibApi.details(). */
public class Title {
    public Site site;
    public int id;
    public String slugUrl;
    public String name;
    public String rusName;
    public String engName;
    public String cover;
    public String thumb;
    public String type;
    public String age;
    public String status;
    public String rating;
    public String year;

    public String summary;
    public final List<String> genres = new ArrayList<>();
    public final List<String> otherNames = new ArrayList<>();

    public String displayName(boolean rus) {
        if (rus && !Text.isEmpty(rusName)) return rusName;
        if (!rus && !Text.isEmpty(engName)) return engName;
        if (!Text.isEmpty(rusName)) return rusName;
        return name;
    }

    public String secondaryName(boolean rus) {
        String main = displayName(rus);
        String other = rus ? (Text.isEmpty(engName) ? name : engName) : rusName;
        return other == null || other.equals(main) ? null : other;
    }

    public static Title parse(JSONObject o, Site site) {
        Title t = new Title();
        t.site = site;
        t.id = o.optInt("id");
        t.slugUrl = o.optString("slug_url");
        t.name = Text.opt(o, "name");
        t.rusName = Text.opt(o, "rus_name");
        t.engName = Text.opt(o, "eng_name");
        JSONObject cover = o.optJSONObject("cover");
        if (cover != null) {
            t.cover = Text.opt(cover, "default");
            t.thumb = Text.opt(cover, "thumbnail");
            if (t.thumb == null) t.thumb = t.cover;
        }
        t.type = label(o, "type");
        t.age = label(o, "ageRestriction");
        t.status = label(o, "status");
        JSONObject rating = o.optJSONObject("rating");
        if (rating != null) t.rating = Text.opt(rating, "averageFormated");
        t.year = Text.opt(o, "releaseDateString");
        Object summary = o.opt("summary");
        if (summary != null && summary != JSONObject.NULL) t.summary = Text.fromDoc(summary).trim();
        names(o.optJSONArray("genres"), t.genres);
        names(o.optJSONArray("tags"), t.genres);
        JSONArray other = o.optJSONArray("otherNames");
        if (other != null) {
            for (int i = 0; i < other.length(); i++) t.otherNames.add(other.optString(i));
        }
        return t;
    }

    private static String label(JSONObject o, String key) {
        JSONObject v = o.optJSONObject(key);
        return v == null ? null : Text.opt(v, "label");
    }

    private static void names(JSONArray arr, List<String> out) {
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject g = arr.optJSONObject(i);
            if (g != null && g.optString("name").length() > 0) out.add(g.optString("name").trim());
        }
    }

    /** Короткая запись для локальной истории. */
    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("site", site.id);
        o.put("id", id);
        o.put("slug_url", slugUrl);
        o.put("name", name);
        o.put("rus_name", rusName);
        o.put("eng_name", engName);
        JSONObject cover = new JSONObject();
        cover.put("default", this.cover);
        cover.put("thumbnail", thumb);
        o.put("cover", cover);
        return o;
    }
}
