package io.github.felixlp.libkat;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

import io.github.felixlp.libkat.core.Episode;
import io.github.felixlp.libkat.core.Player;
import io.github.felixlp.libkat.core.Site;
import io.github.felixlp.libkat.core.Title;

/** Выбор озвучки и запуск серии во встроенном плеере или во внешнем (VLC). */
final class VideoFlow {
    private static final String VLC = "org.videolan.vlc";

    private VideoFlow() {
    }

    static void play(final Activity a, final Title title, final Episode episode) {
        final ProgressDialog progress = ProgressDialog.show(a, null, "Загружаю варианты серии…", true, true);
        Bg.run(new Bg.Job<List<Player>>() {
            @Override
            public List<Player> run() throws Exception {
                return App.get().api().players(episode.id);
            }
        }, new Bg.Done<List<Player>>() {
            @Override
            public void done(List<Player> result, Exception error) {
                if (a.isFinishing()) return;
                progress.dismiss();
                if (error != null) {
                    toast(a, Bg.message(error));
                    return;
                }
                choose(a, title, episode, result);
            }
        });
    }

    private static void choose(final Activity a, final Title title, final Episode episode, List<Player> all) {
        String favourite = App.get().history.team(title.slugUrl);
        final List<Player> direct = new ArrayList<>();
        int kodik = 0;
        for (Player p : all) {
            if (!p.isDirect()) {
                kodik++;
                continue;
            }
            // Любимая озвучка — первой в списке
            if (favourite != null && favourite.equals(p.team)) direct.add(0, p);
            else direct.add(p);
        }
        if (direct.isEmpty()) {
            String msg = !App.get().auth().loggedIn()
                    ? "Плеер AnimeLib доступен после входа в аккаунт (меню → Войти)."
                    : "Для этой серии нет видео в плеере AnimeLib.";
            if (kodik > 0) msg += "\nВариантов Kodik: " + kodik + " — они пока не поддерживаются.";
            new AlertDialog.Builder(a).setMessage(msg).setPositiveButton("OK", null).show();
            return;
        }
        String[] labels = new String[direct.size()];
        for (int i = 0; i < direct.size(); i++) {
            Player p = direct.get(i);
            Player.Quality q = p.pick(App.get().prefs.quality());
            labels[i] = p.label() + (q != null ? " · " + q.quality + "p" : "")
                    + (p.subtitles.isEmpty() ? "" : " · есть субтитры");
        }
        new AlertDialog.Builder(a)
                .setTitle(episode.label())
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Player p = direct.get(which);
                        App.get().history.setTeam(title.slugUrl, p.team);
                        start(a, title, episode, p);
                    }
                })
                .show();
    }

    private static void start(final Activity a, final Title title, final Episode episode, final Player player) {
        final Player.Quality q = player.pick(App.get().prefs.quality());
        Bg.run(new Bg.Job<String[]>() {
            @Override
            public String[] run() throws Exception {
                String referer = Site.ANIMELIB.referer();
                String video = App.get().stream.url(App.get().api().videoUrl(player, q), referer, ".mp4");
                // VLC хорошо рисует ASS, для остальных — VTT
                Player.Subtitle sub = player.subtitle("ass");
                if (sub == null) sub = player.subtitle("vtt");
                String subs = sub == null ? null : App.get().stream.url(sub.src, referer, "." + sub.format);
                return new String[]{video, subs};
            }
        }, new Bg.Done<String[]>() {
            @Override
            public void done(String[] urls, Exception error) {
                if (a.isFinishing()) return;
                if (error != null) {
                    toast(a, Bg.message(error));
                    return;
                }
                App.get().history.markRead(Site.ANIMELIB, title.slugUrl, episode.id);
                App.get().history.setLast(Site.ANIMELIB, title.slugUrl, episode.id, 0);
                String name = title.displayName(App.get().prefs.rusTitles()) + " — " + episode.label();
                String key = episode.id + ":" + player.id;
                if (App.get().prefs.externalPlayer() && external(a, urls[0], urls[1], name)) return;
                if (urls[1] != null && !App.get().prefs.externalPlayer()) {
                    toast(a, "У этой серии есть субтитры — их показывает внешний плеер (VLC), см. настройки");
                }
                PlayerActivity.open(a, urls[0], name, key);
            }
        });
    }

    private static boolean external(Activity a, String video, String subs, String name) {
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(Uri.parse(video), "video/mp4");
        i.putExtra("title", name);
        if (subs != null) i.putExtra("subtitles_location", subs);
        try {
            a.getPackageManager().getPackageInfo(VLC, 0);
            i.setPackage(VLC);
        } catch (Exception ignored) {
            // VLC нет — пусть система предложит, чем открыть
        }
        try {
            a.startActivity(i);
            return true;
        } catch (ActivityNotFoundException e) {
            toast(a, "Нет внешнего видеоплеера, открываю встроенный");
            return false;
        }
    }

    private static void toast(Activity a, String msg) {
        Toast.makeText(a, msg, Toast.LENGTH_LONG).show();
    }
}
