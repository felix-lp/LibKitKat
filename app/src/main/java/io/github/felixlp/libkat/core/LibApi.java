package io.github.felixlp.libkat.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Клиент API *LIB (тот же, что использует сайт). Без Referer и Site-Id сервер отвечает 403. */
public class LibApi {
    public static final String DEFAULT_DOMAIN = "https://api.cdnlibs.org";
    public static final String[] DOMAINS = {
            DEFAULT_DOMAIN, "https://api2.mangalib.me", "https://hapi.hentaicdn.org", "https://api.imglib.info"
    };

    /** Значения sort_by. */
    public static final String SORT_POPULAR = "rating_score";
    public static final String SORT_RATING = "rate_avg";
    public static final String SORT_VIEWS = "views";
    public static final String SORT_NEW = "created_at";
    /** Для аниме заменяется на last_episode_at. */
    public static final String SORT_UPDATED = "last_chapter_at";

    public static final class Paged<T> {
        public final List<T> items;
        public final boolean hasNext;

        Paged(List<T> items, boolean hasNext) {
            this.items = items;
            this.hasNext = hasNext;
        }
    }

    private final OkHttpClient client;
    private final String domain;
    private final Auth auth;
    private List<Server> imageServers;
    private List<Server> videoServers;

    public LibApi(OkHttpClient client, String domain, Auth auth) {
        this.client = client;
        this.domain = domain;
        this.auth = auth;
    }

    private HttpUrl.Builder url(String path) {
        return HttpUrl.parse(domain + "/api" + path).newBuilder();
    }

    /**
     * @param list номер списка закладок (1 — Читаю … 5 — Любимые) или 0 — весь каталог
     */
    public Paged<Title> catalog(Site site, String query, String sortBy, int list, int page) throws IOException {
        HttpUrl.Builder u = url("/" + site.model())
                .addQueryParameter("site_id[]", String.valueOf(site.id))
                .addQueryParameter("page", String.valueOf(page));
        if (!Text.isEmpty(query)) u.addQueryParameter("q", query);
        if (!Text.isEmpty(sortBy)) {
            if (site.anime && SORT_UPDATED.equals(sortBy)) sortBy = "last_episode_at";
            u.addQueryParameter("sort_by", sortBy).addQueryParameter("sort_type", "desc");
        }
        if (list > 0) u.addQueryParameter("bookmarks[]", String.valueOf(list));
        return titles(site, get(site, u.build()));
    }

    public Paged<Title> latest(Site site, int page) throws IOException {
        return titles(site, get(site, url("/latest-updates").addQueryParameter("page", String.valueOf(page)).build()));
    }

    private static Paged<Title> titles(Site site, JSONObject o) {
        JSONArray data = o.optJSONArray("data");
        List<Title> items = new ArrayList<>();
        if (data != null) {
            for (int i = 0; i < data.length(); i++) {
                JSONObject t = data.optJSONObject(i);
                if (t != null) items.add(Title.parse(t, site));
            }
        }
        JSONObject meta = o.optJSONObject("meta");
        boolean next = meta != null && meta.optBoolean("has_next_page", false);
        return new Paged<>(items, next);
    }

    public Title details(Site site, String slug) throws IOException {
        HttpUrl.Builder u = url("/" + site.model() + "/" + slug);
        String[] fields = site.anime
                ? new String[]{"summary", "genres", "tags", "otherNames", "episodes_count"}
                : new String[]{"summary", "genres", "tags", "otherNames", "rate", "chap_count", "authors"};
        for (String f : fields) u.addQueryParameter("fields[]", f);
        return Title.parse(data(get(site, u.build())), site);
    }

    public List<Chapter> chapters(Site site, String slug) throws IOException {
        JSONArray arr = get(site, url("/manga/" + slug + "/chapters").build()).optJSONArray("data");
        List<Chapter> out = new ArrayList<>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject c = arr.optJSONObject(i);
                if (c != null) out.add(Chapter.parse(c));
            }
        }
        return out;
    }

    public List<Page> pages(Site site, String slug, Chapter chapter, Integer branchId) throws IOException {
        HttpUrl.Builder u = url("/manga/" + slug + "/chapter")
                .addQueryParameter("number", chapter.number)
                .addQueryParameter("volume", chapter.volume);
        if (branchId != null) u.addQueryParameter("branch_id", String.valueOf(branchId));
        JSONArray arr = data(get(site, u.build())).optJSONArray("pages");
        List<Page> out = new ArrayList<>();
        if (arr == null) throw new ApiException(404, "Глава недоступна");
        for (int i = 0; i < arr.length(); i++) {
            JSONObject p = arr.optJSONObject(i);
            if (p != null) out.add(Page.parse(p));
        }
        return out;
    }

    public List<Episode> episodes(String slug) throws IOException {
        HttpUrl u = url("/episodes").addQueryParameter("anime_id", slug).build();
        JSONArray arr = get(Site.ANIMELIB, u).optJSONArray("data");
        List<Episode> out = new ArrayList<>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject e = arr.optJSONObject(i);
                if (e != null) out.add(Episode.parse(e));
            }
        }
        return out;
    }

    /** Все варианты серии. Свои плееры Animelib приходят только с токеном. */
    public List<Player> players(int episodeId) throws IOException {
        JSONArray arr = data(get(Site.ANIMELIB, url("/episodes/" + episodeId).build())).optJSONArray("players");
        List<Player> out = new ArrayList<>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject p = arr.optJSONObject(i);
                if (p != null) out.add(Player.parse(p));
            }
        }
        return out;
    }

    public synchronized List<Server> imageServers() throws IOException {
        if (imageServers == null) {
            HttpUrl u = url("/constants").addQueryParameter("fields[]", "imageServers").build();
            imageServers = Server.parseList(data(get(Site.MANGALIB, u)).optJSONArray("imageServers"));
        }
        return Collections.unmodifiableList(imageServers);
    }

    public synchronized List<Server> videoServers() throws IOException {
        if (videoServers == null) {
            HttpUrl u = url("/constants").addQueryParameter("fields[]", "videoServers").build();
            videoServers = Server.parseList(data(get(Site.ANIMELIB, u)).optJSONArray("videoServers"));
        }
        return Collections.unmodifiableList(videoServers);
    }

    /** Полный адрес страницы на выбранном сервере изображений. */
    public String pageUrl(Site site, Page page, String serverId) throws IOException {
        Server s = Server.pick(imageServers(), site, serverId);
        if (s == null) throw new ApiException(0, "Нет сервера изображений для " + site.title);
        return s.url + page.url;
    }

    public String videoUrl(Player player, Player.Quality q) throws IOException {
        Server s = Server.pick(videoServers(), Site.ANIMELIB, player.videoDomain);
        if (s == null) throw new ApiException(0, "Нет видеосервера");
        String base = s.url.endsWith("/") ? s.url.substring(0, s.url.length() - 1) : s.url;
        // В адресе основного сервера есть кириллица (".аs") — HttpUrl закодирует её в %D0%B0
        HttpUrl u = HttpUrl.parse(base + q.href);
        if (u == null) throw new ApiException(0, "Битая ссылка на видео");
        return u.toString();
    }

    public JSONObject me() throws IOException {
        return data(get(Site.MANGALIB, url("/auth/me").build()));
    }

    private static JSONObject data(JSONObject o) throws ApiException {
        JSONObject d = o.optJSONObject("data");
        if (d == null) throw new ApiException(0, "Пустой ответ сервера");
        return d;
    }

    public JSONObject get(Site site, HttpUrl url) throws IOException {
        Token t = auth == null ? null : auth.token();
        if (t != null && t.nearExpiry()) {
            auth.refresh();
            t = auth.token();
        }
        try (Response r = client.newCall(request(site, url, t)).execute()) {
            if (r.code() != 401 || t == null) return parse(r);
        }
        // Токен протух раньше срока — пробуем обновить один раз
        if (auth.refresh()) {
            try (Response r = client.newCall(request(site, url, auth.token())).execute()) {
                return parse(r);
            }
        }
        auth.logout();
        throw new ApiException(401, "Сессия истекла — войдите снова");
    }

    private static Request request(Site site, HttpUrl url, Token t) {
        Request.Builder b = new Request.Builder().url(url)
                .header("Accept", "application/json")
                .header("Site-Id", String.valueOf(site.id))
                .header("Referer", site.referer());
        if (t != null) b.header("Authorization", t.header());
        return b.build();
    }

    private static JSONObject parse(Response r) throws IOException {
        String body = r.body().string();
        if (r.isSuccessful()) {
            try {
                return new JSONObject(body);
            } catch (JSONException e) {
                throw new ApiException(r.code(), "Сервер вернул не JSON");
            }
        }
        throw new ApiException(r.code(), describe(r.code(), body));
    }

    static String describe(int code, String body) {
        String msg = null;
        try {
            JSONObject o = new JSONObject(body);
            JSONObject d = o.optJSONObject("data");
            JSONObject toast = d != null ? d.optJSONObject("toast") : o.optJSONObject("toast");
            if (toast != null) msg = Text.opt(toast, "message");
            if (msg == null) msg = Text.opt(o, "message");
        } catch (JSONException ignored) {
            // HTML-страница DDoS-Guard или nginx — хватит подсказки по коду
        }
        String hint;
        switch (code) {
            case 401: hint = "Нужен вход в аккаунт"; break;
            case 403: hint = "Доступ запрещён (блокировка по IP — попробуйте прокси)"; break;
            case 404: hint = "Не найдено (для 18+ и скрытых тайтлов нужен вход или прокси)"; break;
            case 429: hint = "Слишком много запросов, подождите"; break;
            case 451: hint = "Недоступно в вашем регионе — включите прокси"; break;
            default: hint = "Ошибка сервера HTTP " + code;
        }
        return msg == null || "Not Found".equals(msg) ? hint : hint + ": " + msg;
    }
}
