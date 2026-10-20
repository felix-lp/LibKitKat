package io.github.felixlp.libkat.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Сервер изображений или видео из /api/constants. */
public class Server {
    public String id;
    public String label;
    public String url;
    /** Пустой список — сервер общий для всех сайтов (так у видеосерверов). */
    public final List<Integer> siteIds = new ArrayList<>();

    public boolean supports(Site site) {
        return siteIds.isEmpty() || siteIds.contains(site.id);
    }

    public static List<Server> parseList(JSONArray arr) {
        List<Server> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            Server s = new Server();
            s.id = o.optString("id");
            s.label = o.optString("label");
            s.url = o.optString("url");
            JSONArray ids = o.optJSONArray("site_ids");
            if (ids != null) {
                for (int j = 0; j < ids.length(); j++) s.siteIds.add(ids.optInt(j));
            }
            if (!Text.isEmpty(s.url)) out.add(s);
        }
        return out;
    }

    /** Сервер с нужным id для сайта, иначе первый подходящий. */
    public static Server pick(List<Server> servers, Site site, String id) {
        Server first = null;
        for (Server s : servers) {
            if (!s.supports(site)) continue;
            if (first == null) first = s;
            if (s.id.equals(id)) return s;
        }
        return first;
    }
}
