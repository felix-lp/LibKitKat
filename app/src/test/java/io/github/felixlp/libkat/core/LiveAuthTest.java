package io.github.felixlp.libkat.core;

import org.json.JSONObject;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/** Вход по логину/паролю из LIB_LOGIN / LIB_PASSWORD и то, что открывается только с токеном. */
public class LiveAuthTest {
    static OkHttpClient client;
    static Auth auth;
    static LibApi api;
    static Token saved;

    @BeforeClass
    public static void login() throws Exception {
        assumeTrue("live-тесты выключены", Boolean.getBoolean("live"));
        String login = System.getenv("LIB_LOGIN");
        assumeTrue("нет LIB_LOGIN", login != null && login.length() > 0);
        client = HttpClients.builder(null).build();
        auth = new Auth(client, LibApi.DEFAULT_DOMAIN, new Auth.Store() {
            @Override
            public Token load() {
                return saved;
            }

            @Override
            public void save(Token token) {
                saved = token;
            }
        });
        long t0 = System.currentTimeMillis();
        Token t = auth.login(login, System.getenv("LIB_PASSWORD"), new Auth.Progress() {
            @Override
            public void step(String message) {
                System.out.println("  " + message);
            }
        });
        System.out.println("Вход за " + (System.currentTimeMillis() - t0) + " мс, пользователь " + t.username
                + " #" + t.userId);
        api = new LibApi(client, LibApi.DEFAULT_DOMAIN, auth);
    }

    @Test
    public void tokenWorks() throws Exception {
        assertNotNull(saved);
        assertTrue(saved.userId > 0);
        JSONObject me = api.me();
        assertEquals(saved.userId, me.optInt("id"));
    }

    @Test
    public void bookmarkFoldersAreComplete() throws Exception {
        for (Site site : new Site[]{Site.MANGALIB, Site.ANIMELIB}) {
            List<Folder> folders = api.folders(site, api.userId());
            assertFalse(folders.isEmpty());
            for (Folder f : folders) {
                int n = 0;
                for (int page = 1; page < 50; page++) {
                    LibApi.Paged<Title> p = api.bookmarks(site, api.userId(), f.id, null, page);
                    n += p.items.size();
                    if (!p.hasNext) break;
                }
                System.out.println(site.title + " / " + f.name + ": " + n + " из " + f.count);
                // Лицензированные и закрытые тайтлы тоже должны быть на месте
                assertEquals(f.name, f.count, n);
            }
        }
    }

    @Test
    public void animeDirectVideo() throws Exception {
        Title t = api.catalog(Site.ANIMELIB, "Фрирен", null, 1).items.get(0);
        List<Episode> eps = api.episodes(t.slugUrl);
        List<Player> players = api.players(eps.get(0).id);
        Player direct = null;
        for (Player p : players) {
            if (p.isDirect()) {
                direct = p;
                break;
            }
        }
        assertNotNull("с токеном должен быть плеер Animelib", direct);
        Player.Quality q = direct.pick(720);
        String url = api.videoUrl(direct, q);
        System.out.println(direct.label() + " " + q.quality + "p → " + url);
        try (Response r = client.newCall(new Request.Builder().url(url).header("Range", "bytes=0-1023")
                .header("Referer", Site.ANIMELIB.referer()).build()).execute()) {
            assertEquals(206, r.code());
            assertEquals("video/mp4", r.header("Content-Type"));
        }
    }

    @Test
    public void hentaiPagesWithLogin() throws Exception {
        Title t = api.catalog(Site.HENTAILIB, null, null, 1).items.get(0);
        List<Chapter> chapters = api.chapters(Site.HENTAILIB, t.slugUrl);
        assumeTrue(!chapters.isEmpty());
        List<Page> pages = api.pages(Site.HENTAILIB, t.slugUrl, chapters.get(0), null);
        assertFalse(pages.isEmpty());
        String url = api.pageUrl(Site.HENTAILIB, pages.get(0), "compress");
        try (Response r = client.newCall(new Request.Builder().url(url)
                .header("Referer", Site.HENTAILIB.referer()).build()).execute()) {
            assertEquals(200, r.code());
        }
    }

    @Test
    public void refreshGivesNewToken() throws Exception {
        String before = saved.accessToken;
        assertTrue(auth.refresh());
        assertFalse(before.equals(saved.accessToken));
        assertEquals(saved.userId, api.me().optInt("id"));
    }

    @Test
    public void catalogCarriesBookmarkStatus() throws Exception {
        int marked = 0;
        for (int page = 1; page <= 3; page++) {
            for (Title t : api.catalog(Site.MANGALIB, null, LibApi.SORT_POPULAR, page).items) {
                if (t.bookmarkStatus != 0) marked++;
            }
        }
        System.out.println("В топ-180 с закладкой: " + marked);
        assertTrue(marked > 0);
    }

    /** Добавить → перенести → удалить на тайтле, которого нет в закладках, — списки пользователя не меняются. */
    @Test
    public void bookmarkLifecycleOnFreeTitle() throws Exception {
        Title free = null;
        for (int page = 2; page <= 6 && free == null; page++) {
            for (Title t : api.catalog(Site.MANGALIB, null, LibApi.SORT_POPULAR, page).items) {
                if (t.bookmarkStatus == 0 && api.bookmark(Site.MANGALIB, t.slugUrl) == null) {
                    free = t;
                    break;
                }
            }
        }
        assertNotNull(free);
        List<Folder> folders = api.folders(Site.MANGALIB, api.userId());
        Bookmark b = api.setBookmark(Site.MANGALIB, free.slugUrl, folders.get(1).id);
        try {
            assertEquals(folders.get(1).id, api.bookmark(Site.MANGALIB, free.slugUrl).status);
            Bookmark moved = api.setBookmark(Site.MANGALIB, free.slugUrl, folders.get(2).id);
            assertEquals(b.id, moved.id);
            assertEquals(folders.get(2).id, api.bookmark(Site.MANGALIB, free.slugUrl).status);
        } finally {
            api.deleteBookmark(Site.MANGALIB, b.id);
        }
        assertEquals(null, api.bookmark(Site.MANGALIB, free.slugUrl));
        System.out.println("Закладка туда-обратно на «" + free.displayName(true) + "» — ок");
    }

    @Test
    public void commentsForTitleChapterAnimeEpisode() throws Exception {
        Title manga = api.catalog(Site.MANGALIB, null, LibApi.SORT_POPULAR, 1).items.get(0);
        LibApi.Paged<Comment> c = api.comments(Site.MANGALIB, LibApi.POST_MANGA, manga.id, 1);
        assertFalse(c.items.isEmpty());
        assertTrue(c.items.get(0).html.length() > 0);
        int replies = 0;
        for (Comment x : c.items) if (x.level > 0) replies++;
        System.out.println("Комментарии к «" + manga.displayName(true) + "»: " + c.items.size() + " (ответов " + replies
                + "), первый от " + c.items.get(0).user);
        Chapter ch = api.chapters(Site.MANGALIB, manga.slugUrl).get(0);
        assertNotNull(api.comments(Site.MANGALIB, LibApi.POST_CHAPTER, ch.branch(null).id, 1));

        Title anime = api.catalog(Site.ANIMELIB, "Фрирен", null, 1).items.get(0);
        assertFalse(api.comments(Site.ANIMELIB, LibApi.POST_ANIME, anime.id, 1).items.isEmpty());
        Episode e = api.episodes(anime.slugUrl).get(0);
        assertFalse(api.comments(Site.ANIMELIB, LibApi.POST_EPISODE, e.id, 1).items.isEmpty());
    }
}
