package io.github.felixlp.libkat.core;

/** Сайты семейства *LIB: общий API, различаются только Site-Id и Referer. */
public enum Site {
    MANGALIB(1, "MangaLib", "https://mangalib.org", false),
    SLASHLIB(2, "SlashLib", "https://slashlib.me", false),
    HENTAILIB(4, "HentaiLib", "https://hentailib.me", false),
    ANIMELIB(5, "AnimeLib", "https://animelib.org", true);

    public final int id;
    public final String title;
    public final String web;
    public final boolean anime;

    Site(int id, String title, String web, boolean anime) {
        this.id = id;
        this.title = title;
        this.web = web;
        this.anime = anime;
    }

    public String referer() {
        return web + "/";
    }

    /** Сегмент пути в API: /api/manga/... или /api/anime/... */
    public String model() {
        return anime ? "anime" : "manga";
    }

    public static Site byId(int id) {
        for (Site s : values()) {
            if (s.id == id) return s;
        }
        return MANGALIB;
    }
}
