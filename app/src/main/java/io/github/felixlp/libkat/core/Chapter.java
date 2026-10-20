package io.github.felixlp.libkat.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Глава. У одной главы может быть несколько переводов (веток). */
public class Chapter {
    public int id;
    public String volume;
    public String number;
    public String name;
    public final List<Branch> branches = new ArrayList<>();

    public static class Branch {
        /** null — ветка по умолчанию (у тайтлов с одним переводом). */
        public Integer branchId;
        public String teams;
        public String createdAt;
        /** false — платная/закрытая глава. */
        public boolean open = true;
    }

    public String label() {
        String s = "Том " + volume + " Глава " + number;
        return Text.isEmpty(name) ? s : s + " — " + name;
    }

    /** Ветка с нужным id или первая, если такой нет. */
    public Branch branch(Integer branchId) {
        if (branches.isEmpty()) return null;
        if (branchId != null) {
            for (Branch b : branches) {
                if (branchId.equals(b.branchId)) return b;
            }
        }
        return branches.get(0);
    }

    public static Chapter parse(JSONObject o) {
        Chapter c = new Chapter();
        c.id = o.optInt("id");
        c.volume = o.optString("volume");
        c.number = o.optString("number");
        c.name = Text.opt(o, "name");
        JSONArray arr = o.optJSONArray("branches");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject b = arr.optJSONObject(i);
                if (b == null) continue;
                Branch br = new Branch();
                br.branchId = b.isNull("branch_id") ? null : b.optInt("branch_id");
                br.createdAt = Text.opt(b, "created_at");
                JSONArray teams = b.optJSONArray("teams");
                List<String> names = new ArrayList<>();
                if (teams != null) {
                    for (int j = 0; j < teams.length(); j++) {
                        JSONObject t = teams.optJSONObject(j);
                        if (t != null) names.add(t.optString("name"));
                    }
                }
                if (names.isEmpty()) {
                    JSONObject user = b.optJSONObject("user");
                    if (user != null) names.add(user.optString("username"));
                }
                br.teams = Text.join(", ", names);
                JSONObject rv = b.optJSONObject("restricted_view");
                if (rv != null) br.open = rv.optBoolean("is_open", true);
                c.branches.add(br);
            }
        }
        return c;
    }
}
