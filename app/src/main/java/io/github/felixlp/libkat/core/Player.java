package io.github.felixlp.libkat.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Вариант серии: конкретная озвучка/субтитры в плеере Animelib или Kodik. */
public class Player {
    public static final String ANIMELIB = "Animelib";

    public int id;
    public String player;
    public String translationType;
    public String team;
    /** Ссылка на iframe (только Kodik). */
    public String src;
    public String videoDomain;
    public final List<Quality> qualities = new ArrayList<>();
    public final List<Subtitle> subtitles = new ArrayList<>();

    public static class Quality {
        public String href;
        public int quality;
        public int bitrate;
    }

    public static class Subtitle {
        public String format;
        public String src;
    }

    public boolean isDirect() {
        return ANIMELIB.equals(player) && !qualities.isEmpty();
    }

    public String label() {
        String s = (team == null ? "?" : team) + " — " + (translationType == null ? "" : translationType);
        return isDirect() ? s : s + " (" + player + ")";
    }

    /** Лучшее качество не выше preferred; если таких нет — самое низкое. */
    public Quality pick(int preferred) {
        Quality best = null;
        Quality lowest = null;
        for (Quality q : qualities) {
            if (q.quality <= preferred && (best == null || q.quality > best.quality)) best = q;
            if (lowest == null || q.quality < lowest.quality) lowest = q;
        }
        return best != null ? best : lowest;
    }

    public Subtitle subtitle(String format) {
        for (Subtitle s : subtitles) {
            if (format.equals(s.format)) return s;
        }
        return null;
    }

    public static Player parse(JSONObject o) {
        Player p = new Player();
        p.id = o.optInt("id");
        p.player = o.optString("player");
        JSONObject tt = o.optJSONObject("translation_type");
        if (tt != null) p.translationType = Text.opt(tt, "label");
        JSONObject team = o.optJSONObject("team");
        if (team != null) p.team = Text.opt(team, "name");
        p.src = Text.opt(o, "src");
        p.videoDomain = Text.opt(o, "video_domain");
        JSONObject video = o.optJSONObject("video");
        JSONArray q = video == null ? null : video.optJSONArray("quality");
        if (q != null) {
            for (int i = 0; i < q.length(); i++) {
                JSONObject qo = q.optJSONObject(i);
                if (qo == null) continue;
                Quality qq = new Quality();
                qq.href = qo.optString("href");
                qq.quality = qo.optInt("quality");
                qq.bitrate = qo.optInt("bitrate");
                p.qualities.add(qq);
            }
        }
        JSONArray subs = o.optJSONArray("subtitles");
        if (subs != null) {
            for (int i = 0; i < subs.length(); i++) {
                JSONObject so = subs.optJSONObject(i);
                if (so == null) continue;
                Subtitle s = new Subtitle();
                s.format = so.optString("format");
                s.src = so.optString("src");
                p.subtitles.add(s);
            }
        }
        return p;
    }
}
