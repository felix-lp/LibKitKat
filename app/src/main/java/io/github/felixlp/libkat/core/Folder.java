package io.github.felixlp.libkat.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Папка закладок: стандартные («Читаю», «В планах»…) и созданные пользователем. */
public class Folder {
    public int id;
    public String name;
    public int count;
    public final List<Integer> siteIds = new ArrayList<>();

    public boolean supports(Site site) {
        return siteIds.contains(site.id);
    }

    public String label() {
        return count > 0 ? name + " (" + count + ")" : name;
    }

    static Folder parse(JSONObject o) {
        Folder f = new Folder();
        f.id = o.optInt("id");
        f.name = o.optString("name");
        f.count = o.optInt("count");
        JSONArray ids = o.optJSONArray("site_ids");
        if (ids != null) {
            for (int i = 0; i < ids.length(); i++) f.siteIds.add(ids.optInt(i));
        }
        return f;
    }
}
