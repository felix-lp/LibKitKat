package io.github.felixlp.libkat.core;

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

/**
 * Проверки против настоящего API. Запуск: ./gradlew testDebugUnitTest -Dlive=true
 * (с LIB_LOGIN/LIB_PASSWORD в окружении — ещё и вход).
 */
public class LiveApiTest {
    static OkHttpClient client;
    static LibApi api;

    @BeforeClass
    public static void setUp() {
        assumeTrue("live-тесты выключены", Boolean.getBoolean("live"));
        client = HttpClients.builder(null).build();
        api = new LibApi(client, LibApi.DEFAULT_DOMAIN, null);
    }

    @Test
    public void mangaCatalogDetailsChaptersPages() throws Exception {
        LibApi.Paged<Title> page = api.catalog(Site.MANGALIB, null, LibApi.SORT_POPULAR, 0, 1);
        assertTrue(page.items.size() > 10);
        assertTrue(page.hasNext);
        Title first = page.items.get(0);
        System.out.println("Популярное: " + first.displayName(true) + " / " + first.slugUrl);

        LibApi.Paged<Title> found = api.catalog(Site.MANGALIB, "Берсерк", null, 0, 1);
        assertFalse(found.items.isEmpty());
        Title t = found.items.get(0);
        System.out.println("Поиск «Берсерк»: " + t.displayName(true) + " " + t.slugUrl);

        Title d = api.details(Site.MANGALIB, t.slugUrl);
        assertNotNull(d.summary);
        assertFalse(d.genres.isEmpty());

        List<Chapter> chapters = api.chapters(Site.MANGALIB, t.slugUrl);
        assertFalse(chapters.isEmpty());
        Chapter c = chapters.get(0);
        System.out.println("Глав: " + chapters.size() + ", первая: " + c.label() + " [" + c.branch(null).teams + "]");

        List<Page> pages = api.pages(Site.MANGALIB, t.slugUrl, c, c.branch(null).branchId);
        assertFalse(pages.isEmpty());
        assertTrue(pages.get(0).width > 0);
        String url = api.pageUrl(Site.MANGALIB, pages.get(0), "compress");
        System.out.println("Страниц: " + pages.size() + ", первая: " + url);
        try (Response r = client.newCall(new Request.Builder().url(url)
                .header("Referer", Site.MANGALIB.referer()).build()).execute()) {
            assertEquals(200, r.code());
            assertTrue(r.header("Content-Type").startsWith("image/"));
        }
    }

    @Test
    public void sortsAndLatest() throws Exception {
        for (String sort : new String[]{LibApi.SORT_RATING, LibApi.SORT_VIEWS, LibApi.SORT_NEW, LibApi.SORT_UPDATED}) {
            assertFalse(sort, api.catalog(Site.MANGALIB, null, sort, 0, 1).items.isEmpty());
            assertFalse(sort, api.catalog(Site.ANIMELIB, null, sort, 0, 1).items.isEmpty());
        }
        assertFalse(api.latest(Site.MANGALIB, 1).items.isEmpty());
    }

    @Test
    public void otherSites() throws Exception {
        for (Site s : new Site[]{Site.HENTAILIB, Site.SLASHLIB}) {
            LibApi.Paged<Title> p = api.catalog(s, null, null, 0, 1);
            assertFalse(s.title, p.items.isEmpty());
            assertEquals(s, p.items.get(0).site);
            System.out.println(s.title + ": " + p.items.get(0).displayName(true));
        }
        assertTrue(Server.pick(api.imageServers(), Site.HENTAILIB, "compress").url.contains("hentaicdn"));
    }

    @Test
    public void animeWithoutLoginHasOnlyKodik() throws Exception {
        Title t = api.catalog(Site.ANIMELIB, "Фрирен", null, 0, 1).items.get(0);
        System.out.println("Аниме: " + t.displayName(true) + " " + t.slugUrl);
        Title d = api.details(Site.ANIMELIB, t.slugUrl);
        assertNotNull(d.summary);
        List<Episode> eps = api.episodes(t.slugUrl);
        assertFalse(eps.isEmpty());
        List<Player> players = api.players(eps.get(0).id);
        assertFalse(players.isEmpty());
        for (Player p : players) assertFalse("без входа не должно быть прямых ссылок", p.isDirect());
        assertFalse(api.videoServers().isEmpty());
    }

    @Test
    public void hiddenChapterNeedsLogin() throws Exception {
        Title t = api.catalog(Site.HENTAILIB, null, null, 0, 1).items.get(0);
        List<Chapter> chapters = api.chapters(Site.HENTAILIB, t.slugUrl);
        assumeTrue(!chapters.isEmpty());
        try {
            api.pages(Site.HENTAILIB, t.slugUrl, chapters.get(0), null);
            System.out.println("HentaiLib отдал страницы без входа");
        } catch (ApiException e) {
            System.out.println("HentaiLib без входа: " + e.code + " " + e.getMessage());
            assertEquals(404, e.code);
        }
    }
}
