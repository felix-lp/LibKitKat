package io.github.felixlp.libkat.core;

import org.json.JSONObject;

public class Episode {
    public int id;
    public String name;
    public String number;
    public String season;
    public String status;

    public String label() {
        String s = "Сезон " + season + " Эпизод " + number;
        return Text.isEmpty(name) ? s : s + " — " + name;
    }

    public static Episode parse(JSONObject o) {
        Episode e = new Episode();
        e.id = o.optInt("id");
        e.name = Text.opt(o, "name");
        e.number = o.optString("number");
        e.season = o.optString("season");
        JSONObject st = o.optJSONObject("status");
        if (st != null) e.status = Text.opt(st, "label");
        return e;
    }
}
