package io.github.felixlp.libkat;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import io.github.felixlp.libkat.core.ApiException;
import io.github.felixlp.libkat.core.Text;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Обновления из GitHub Releases: проверяет последний релиз, качает APK и открывает установщик.
 * Приватный репозиторий читается с токеном из настроек, публичный — анонимно.
 */
final class Updater {
    static final String AUTO = "update_auto";
    static final String TOKEN = "github_token";
    private static final String LAST_CHECK = "update_last_check";
    private static final String SKIPPED = "update_skipped";
    private static final long AUTO_INTERVAL = 12L * 3600 * 1000;
    static final String APK_NAME = "LibKat-update.apk";

    private Updater() {
    }

    static final class Release {
        String tag;
        String version;
        String notes;
        String apkUrl;
        long size;
        /** Ссылка на API-ресурс (нужен токен и Accept: octet-stream), а не на прямую загрузку. */
        boolean viaApi;
    }

    static String currentVersion() {
        return BuildConfig.VERSION_NAME;
    }

    /** Сравнение версий вида 1.2.3: отрицательное — a старше b. */
    static int compare(String a, String b) {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? parse(x[i]) : 0;
            int q = i < y.length ? parse(y[i]) : 0;
            if (p != q) return p < q ? -1 : 1;
        }
        return 0;
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String token() {
        return App.get().prefs.sp.getString(TOKEN, "").trim();
    }

    private static Request.Builder github(String url) {
        Request.Builder b = new Request.Builder().url(url)
                .header("User-Agent", "LibKat/" + currentVersion())
                .header("X-GitHub-Api-Version", "2022-11-28");
        String t = token();
        if (t.length() > 0) b.header("Authorization", "Bearer " + t);
        return b;
    }

    static Release fetch() throws Exception {
        String url = "https://api.github.com/repos/" + BuildConfig.UPDATE_REPO + "/releases/latest";
        JSONObject o;
        try (Response r = App.get().directClient().newCall(github(url)
                .header("Accept", "application/vnd.github+json").build()).execute()) {
            String body = r.body().string();
            if (r.code() == 404) {
                throw new ApiException(404, token().length() == 0
                        ? "Релиз не найден. Если репозиторий приватный — укажите токен GitHub в настройках"
                        : "Релиз не найден (или у токена нет доступа к репозиторию)");
            }
            if (r.code() == 401) throw new ApiException(401, "GitHub не принял токен");
            if (r.code() == 403) throw new ApiException(403, "GitHub ограничил запросы, попробуйте позже");
            if (!r.isSuccessful()) throw new ApiException(r.code(), "GitHub ответил HTTP " + r.code());
            o = new JSONObject(body);
        }
        Release rel = new Release();
        rel.tag = o.optString("tag_name");
        rel.version = rel.tag.startsWith("v") ? rel.tag.substring(1) : rel.tag;
        rel.notes = Text.opt(o, "body");
        JSONArray assets = o.optJSONArray("assets");
        for (int i = 0; assets != null && i < assets.length(); i++) {
            JSONObject a = assets.optJSONObject(i);
            if (a == null || !a.optString("name").endsWith(".apk")) continue;
            rel.size = a.optLong("size");
            // Из приватного репозитория файл отдаётся только через API с токеном
            rel.viaApi = token().length() > 0;
            rel.apkUrl = rel.viaApi ? a.optString("url") : a.optString("browser_download_url");
            break;
        }
        if (rel.apkUrl == null) throw new ApiException(0, "В релизе " + rel.tag + " нет APK");
        return rel;
    }

    /** Тихая проверка при запуске: не чаще раза в 12 часов, ошибки не показываем. */
    static void checkAuto(final Activity a) {
        final SharedPreferences sp = App.get().prefs.sp;
        if (!sp.getBoolean(AUTO, true)) return;
        long now = System.currentTimeMillis();
        if (now - sp.getLong(LAST_CHECK, 0) < AUTO_INTERVAL) return;
        sp.edit().putLong(LAST_CHECK, now).apply();
        Bg.run(new Bg.Job<Release>() {
            @Override
            public Release run() throws Exception {
                return fetch();
            }
        }, new Bg.Done<Release>() {
            @Override
            public void done(Release r, Exception error) {
                if (error != null || a.isFinishing()) return;
                if (compare(r.version, currentVersion()) <= 0) return;
                if (r.tag.equals(sp.getString(SKIPPED, null))) return;
                offer(a, r);
            }
        });
    }

    /** Проверка по кнопке: сообщаем и «у вас последняя», и ошибки. */
    static void checkNow(final Activity a) {
        final ProgressDialog progress = ProgressDialog.show(a, null, "Проверяю обновления…", true, true);
        Bg.run(new Bg.Job<Release>() {
            @Override
            public Release run() throws Exception {
                return fetch();
            }
        }, new Bg.Done<Release>() {
            @Override
            public void done(Release r, Exception error) {
                if (a.isFinishing()) return;
                progress.dismiss();
                App.get().prefs.sp.edit().putLong(LAST_CHECK, System.currentTimeMillis()).apply();
                if (error != null) {
                    Toast.makeText(a, Bg.message(error), Toast.LENGTH_LONG).show();
                } else if (compare(r.version, currentVersion()) > 0) {
                    offer(a, r);
                } else {
                    Toast.makeText(a, "У вас последняя версия " + currentVersion(), Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    private static void offer(final Activity a, final Release r) {
        String notes = r.notes == null ? "" : r.notes.trim();
        if (notes.length() > 1500) notes = notes.substring(0, 1500) + "…";
        String size = r.size > 0 ? " (" + (r.size / (1024 * 1024)) + " МБ)" : "";
        new AlertDialog.Builder(a)
                .setTitle("Обновление " + r.version + size)
                .setMessage("Сейчас установлена " + currentVersion() + (notes.length() > 0 ? "\n\n" + notes : ""))
                .setPositiveButton("Обновить", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        download(a, r);
                    }
                })
                .setNeutralButton("Пропустить версию", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        App.get().prefs.sp.edit().putString(SKIPPED, r.tag).apply();
                    }
                })
                .setNegativeButton("Позже", null)
                .show();
    }

    /** На Android 7+ файл отдаётся установщику через ApkProvider, раньше — по file:// из своей папки. */
    static File updateDir(Context c) {
        File dir = new File(c.getFilesDir(), "updates");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    private static void download(final Activity a, final Release r) {
        final ProgressDialog progress = new ProgressDialog(a);
        progress.setMessage("Скачиваю " + r.version + "…");
        progress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progress.setMax(100);
        progress.setCancelable(false);
        progress.show();
        final File target = new File(updateDir(a), APK_NAME);
        Bg.run(new Bg.Job<File>() {
            @Override
            public File run() throws Exception {
                Request.Builder rb = github(r.apkUrl);
                if (r.viaApi) rb.header("Accept", "application/octet-stream");
                // OkHttp при редиректе на другой хост (хранилище GitHub) сам убирает Authorization
                try (Response resp = App.get().directClient().newCall(rb.build()).execute()) {
                    if (!resp.isSuccessful()) throw new ApiException(resp.code(), "Загрузка: HTTP " + resp.code());
                    long total = resp.body().contentLength();
                    if (total <= 0) total = r.size;
                    File tmp = new File(target.getPath() + ".part");
                    try (InputStream in = resp.body().byteStream(); OutputStream out = new FileOutputStream(tmp)) {
                        byte[] buf = new byte[65536];
                        long done = 0;
                        int last = -1;
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            out.write(buf, 0, n);
                            done += n;
                            final int pct = total > 0 ? (int) (done * 100 / total) : 0;
                            if (pct != last) {
                                last = pct;
                                Bg.MAIN.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        progress.setProgress(pct);
                                    }
                                });
                            }
                        }
                    }
                    if (!tmp.renameTo(target)) throw new IOException("Не удалось сохранить APK");
                }
                // Убеждаемся, что скачали именно новую версию этого приложения
                PackageInfo info = a.getPackageManager().getPackageArchiveInfo(target.getPath(), 0);
                if (info == null || !a.getPackageName().equals(info.packageName)) {
                    throw new IOException("Скачанный файл — не APK LibKat");
                }
                return target;
            }
        }, new Bg.Done<File>() {
            @Override
            public void done(File file, Exception error) {
                if (a.isFinishing()) return;
                progress.dismiss();
                if (error != null) {
                    Toast.makeText(a, "Не удалось скачать: " + Bg.message(error), Toast.LENGTH_LONG).show();
                    return;
                }
                install(a, file);
            }
        });
    }

    @SuppressWarnings("SetWorldReadable")
    private static void install(Activity a, File apk) {
        Intent i = new Intent(Intent.ACTION_VIEW);
        Uri uri;
        if (Build.VERSION.SDK_INT >= 24) {
            uri = ApkProvider.uri(a);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else {
            // Установщик до Android 7 читает только file:// — открываем файл на чтение всем
            //noinspection ResultOfMethodCallIgnored
            apk.setReadable(true, false);
            //noinspection ResultOfMethodCallIgnored
            apk.getParentFile().setExecutable(true, false);
            //noinspection ResultOfMethodCallIgnored
            a.getFilesDir().setExecutable(true, false);
            uri = Uri.fromFile(apk);
        }
        i.setDataAndType(uri, "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            a.startActivity(i);
        } catch (Exception e) {
            Toast.makeText(a, "Не удалось открыть установщик: " + Bg.message(e), Toast.LENGTH_LONG).show();
        }
    }
}
