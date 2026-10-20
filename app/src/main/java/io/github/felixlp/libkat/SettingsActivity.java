package io.github.felixlp.libkat;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.EditTextPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;

import org.json.JSONObject;

import java.util.Arrays;
import java.util.List;

import io.github.felixlp.libkat.core.HttpClients;
import io.github.felixlp.libkat.core.Site;
import io.github.felixlp.libkat.core.Text;
import io.github.felixlp.libkat.core.Token;
import okhttp3.Request;
import okhttp3.Response;

@SuppressWarnings("deprecation")
public class SettingsActivity extends PreferenceActivity implements SharedPreferences.OnSharedPreferenceChangeListener {
    /** После их смены сетевые клиенты пересобираются. */
    private static final List<String> NETWORK_KEYS = Arrays.asList(
            Prefs.API_DOMAIN, Prefs.PROXY_MODE, Prefs.PROXY_HOST, Prefs.PROXY_PORT, Prefs.PROXY_USER, Prefs.PROXY_PASS);
    private static final List<String> SUMMARY_KEYS = Arrays.asList(
            Prefs.PROXY_MODE, Prefs.PROXY_HOST, Prefs.PROXY_PORT, Prefs.PROXY_USER, Prefs.API_DOMAIN,
            Prefs.IMAGE_SERVER, Prefs.TITLE_LANG, Prefs.PLAYER, Prefs.QUALITY);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.prefs);
        findPreference("account").setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference p) {
                startActivity(new Intent(SettingsActivity.this, LoginActivity.class));
                return true;
            }
        });
        findPreference("proxy_test").setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference p) {
                testConnection();
                return true;
            }
        });
        findPreference("clear_cache").setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(final Preference p) {
                Bg.run(new Bg.Job<Void>() {
                    @Override
                    public Void run() {
                        App.get().images.clear();
                        return null;
                    }
                }, new Bg.Done<Void>() {
                    @Override
                    public void done(Void result, Exception error) {
                        updateCacheSize();
                    }
                });
                return true;
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        getPreferenceScreen().getSharedPreferences().registerOnSharedPreferenceChangeListener(this);
        for (String k : SUMMARY_KEYS) updateSummary(k);
        Token t = App.get().auth().token();
        findPreference("account").setSummary(t == null ? "Не выполнен — 18+, закладки и плеер AnimeLib недоступны"
                : "Вы вошли как " + (Text.isEmpty(t.username) ? "#" + t.userId : t.username));
        updateCacheSize();
    }

    @Override
    protected void onPause() {
        super.onPause();
        getPreferenceScreen().getSharedPreferences().unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
        if (NETWORK_KEYS.contains(key)) App.get().rebuild();
        updateSummary(key);
    }

    private void updateSummary(String key) {
        Preference p = findPreference(key);
        if (p instanceof ListPreference) {
            p.setSummary(((ListPreference) p).getEntry());
        } else if (p instanceof EditTextPreference) {
            String v = ((EditTextPreference) p).getText();
            p.setSummary(Text.isEmpty(v) ? "не задан" : v);
        }
    }

    private void updateCacheSize() {
        Bg.run(new Bg.Job<Long>() {
            @Override
            public Long run() {
                return App.get().images.diskSize();
            }
        }, new Bg.Done<Long>() {
            @Override
            public void done(Long size, Exception error) {
                if (size != null) findPreference("clear_cache").setSummary("Сейчас: " + (size / (1024 * 1024)) + " МБ");
            }
        });
    }

    /** Внешний IP через тот же клиент, что и API, плюс пробный запрос к API. */
    private void testConnection() {
        final AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Проверка").setMessage("Проверяю…")
                .setPositiveButton("OK", null).show();
        Bg.run(new Bg.Job<String>() {
            @Override
            public String run() {
                StringBuilder sb = new StringBuilder();
                sb.append(App.get().prefs.proxy().forApi() ? "API через прокси\n" : "API напрямую\n");
                long t0 = System.currentTimeMillis();
                try (Response r = App.get().apiClient().newCall(new Request.Builder()
                        .url("https://api.ipify.org?format=json").header("User-Agent", HttpClients.USER_AGENT)
                        .build()).execute()) {
                    sb.append("Внешний IP: ").append(new JSONObject(r.body().string()).optString("ip"));
                } catch (Exception e) {
                    sb.append("IP: ").append(Bg.message(e));
                }
                sb.append('\n');
                try {
                    t0 = System.currentTimeMillis();
                    int n = App.get().api().catalog(Site.MANGALIB, null, null, 0, 1).items.size();
                    sb.append("API: работает (").append(System.currentTimeMillis() - t0).append(" мс, тайтлов: ")
                            .append(n).append(')');
                } catch (Exception e) {
                    sb.append("API: ").append(Bg.message(e));
                }
                return sb.toString();
            }
        }, new Bg.Done<String>() {
            @Override
            public void done(String result, Exception error) {
                if (dialog.isShowing()) dialog.setMessage(error != null ? Bg.message(error) : result);
            }
        });
    }
}
