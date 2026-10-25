package io.github.felixlp.libkat.core;

import org.json.JSONObject;

import java.util.Comparator;

public class Comment {
    public int id;
    public int rootId;
    public int level;
    /** Текст в HTML (p, span, div). */
    public String html;
    public long createdAt;
    public String user;
    public int up;
    public int down;

    static final Comparator<Comment> BY_TIME = new Comparator<Comment>() {
        @Override
        public int compare(Comment a, Comment b) {
            return a.createdAt < b.createdAt ? -1 : (a.createdAt == b.createdAt ? 0 : 1);
        }
    };

    static Comment parse(JSONObject o) {
        Comment c = new Comment();
        c.id = o.optInt("id");
        c.rootId = o.optInt("root_id");
        c.level = o.optInt("comment_level");
        c.html = o.optString("comment");
        c.createdAt = o.optLong("created_at_ts");
        JSONObject u = o.optJSONObject("user");
        c.user = u == null ? "?" : u.optString("username");
        JSONObject v = o.optJSONObject("votes");
        if (v != null) {
            c.up = v.optInt("up");
            c.down = v.optInt("down");
        }
        return c;
    }
}
