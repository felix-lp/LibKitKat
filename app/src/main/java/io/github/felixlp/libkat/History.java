package io.github.felixlp.libkat;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.felixlp.libkat.core.Site;
import io.github.felixlp.libkat.core.Title;

/** Локальная история: последние тайтлы, прочитанные главы, место остановки. */
public class History {
    private static final int MAX_TITLES = 200;

    private final SharedPreferences sp;

    History(Context context) {
        sp = context.getSharedPreferences("history", Context.MODE_PRIVATE);
    }

    public synchronized List<Title> titles(Site site) {
        List<Title> out = new ArrayList<>();
        JSONArray arr = list();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && o.optInt("site") == site.id) out.add(Title.parse(o, site));
        }
        return out;
    }

    public synchronized void touch(Title t) {
        try {
            JSONArray old = list();
            JSONArray arr = new JSONArray();
            arr.put(t.toJson());
            for (int i = 0; i < old.length() && arr.length() < MAX_TITLES; i++) {
                JSONObject o = old.optJSONObject(i);
                if (o == null) continue;
                if (o.optInt("site") == t.site.id && t.slugUrl.equals(o.optString("slug_url"))) continue;
                arr.put(o);
            }
            sp.edit().putString("titles", arr.toString()).apply();
        } catch (JSONException ignored) {
            // История — не критично
        }
    }

    private JSONArray list() {
        try {
            return new JSONArray(sp.getString("titles", "[]"));
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    private static String key(String kind, Site site, String slug) {
        return kind + ":" + site.id + ":" + slug;
    }

    public synchronized Set<Integer> read(Site site, String slug) {
        Set<Integer> out = new HashSet<>();
        String s = sp.getString(key("r", site, slug), "");
        for (String part : s.split(",")) {
            if (part.length() == 0) continue;
            try {
                out.add(Integer.parseInt(part));
            } catch (NumberFormatException ignored) {
                // пропускаем мусор
            }
        }
        return out;
    }

    public synchronized void markRead(Site site, String slug, int id) {
        Set<Integer> ids = read(site, slug);
        if (!ids.add(id)) return;
        StringBuilder sb = new StringBuilder();
        for (Integer i : ids) {
            if (sb.length() > 0) sb.append(',');
            sb.append(i);
        }
        sp.edit().putString(key("r", site, slug), sb.toString()).apply();
    }

    /** Последняя открытая глава/серия и страница в ней. */
    public synchronized void setLast(Site site, String slug, int itemId, int page) {
        sp.edit().putString(key("l", site, slug), itemId + ":" + page).apply();
    }

    public synchronized int lastItem(Site site, String slug) {
        String[] p = sp.getString(key("l", site, slug), "").split(":");
        try {
            return Integer.parseInt(p[0]);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    public synchronized int lastPage(Site site, String slug, int itemId) {
        String[] p = sp.getString(key("l", site, slug), "").split(":");
        try {
            return Integer.parseInt(p[0]) == itemId ? Integer.parseInt(p[1]) : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** Выбранный перевод манги (branch_id) или null. */
    public synchronized Integer branch(Site site, String slug) {
        int b = sp.getInt(key("b", site, slug), -1);
        return b < 0 ? null : b;
    }

    public synchronized void setBranch(Site site, String slug, Integer branch) {
        sp.edit().putInt(key("b", site, slug), branch == null ? -1 : branch).apply();
    }

    /** Режим читалки, выбранный для тайтла в меню, или null. */
    public synchronized String readerMode(Site site, String slug) {
        return sp.getString(key("m", site, slug), null);
    }

    public synchronized void setReaderMode(Site site, String slug, String mode) {
        sp.edit().putString(key("m", site, slug), mode).apply();
    }

    /** Любимая озвучка аниме. */
    public synchronized String team(String slug) {
        return sp.getString(key("t", Site.ANIMELIB, slug), null);
    }

    public synchronized void setTeam(String slug, String team) {
        sp.edit().putString(key("t", Site.ANIMELIB, slug), team).apply();
    }

    public synchronized int position(String videoKey) {
        return sp.getInt("v:" + videoKey, 0);
    }

    public synchronized void setPosition(String videoKey, int ms) {
        sp.edit().putInt("v:" + videoKey, ms).apply();
    }
}
