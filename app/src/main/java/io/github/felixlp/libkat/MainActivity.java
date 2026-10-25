package io.github.felixlp.libkat;

import android.app.ActionBar;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.github.felixlp.libkat.core.Folder;
import io.github.felixlp.libkat.core.LibApi;
import io.github.felixlp.libkat.core.Site;
import io.github.felixlp.libkat.core.Text;
import io.github.felixlp.libkat.core.Title;

public class MainActivity extends Activity {
    /** После «Истории» в списке идут папки закладок аккаунта — они свои у каждого сайта. */
    private static final String[] BASE_MODES = {
            "Популярное", "По рейтингу", "По просмотрам", "Новинки", "Обновлённые", "История"
    };
    private static final String[] SORTS = {
            LibApi.SORT_POPULAR, LibApi.SORT_RATING, LibApi.SORT_VIEWS, LibApi.SORT_NEW, LibApi.SORT_UPDATED
    };
    private static final int MODE_HISTORY = 5;

    private final List<Title> items = new ArrayList<>();
    private final List<Folder> folders = new ArrayList<>();
    /** Все папки сайта (и пустые) — для подписи значков на обложках. */
    private final Map<Integer, Folder> folderById = new HashMap<>();
    private final List<String> modeLabels = new ArrayList<>(Arrays.asList(BASE_MODES));
    private Site site;
    private int mode;
    private String query = "";
    private int page;
    private boolean hasNext;
    private boolean loading;
    /** Номер запроса: ответы на устаревшие запросы игнорируются. */
    private int generation;
    private int folderGeneration;

    private EditText search;
    private TextView status;
    private TitleAdapter adapter;
    private Spinner modeSpinner;
    private ArrayAdapter<String> modesAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        site = App.get().prefs.site();

        search = (EditText) findViewById(R.id.search);
        status = (TextView) findViewById(R.id.status);
        GridView grid = (GridView) findViewById(R.id.grid);
        adapter = new TitleAdapter(this);
        grid.setAdapter(adapter);
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                TitleActivity.open(MainActivity.this, items.get(position));
            }
        });
        grid.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {
            }

            @Override
            public void onScroll(AbsListView view, int first, int visible, int total) {
                if (total > 0 && first + visible >= total - 6) loadMore();
            }
        });

        modeSpinner = (Spinner) findViewById(R.id.mode);
        modesAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, modeLabels);
        modesAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        modeSpinner.setAdapter(modesAdapter);
        modeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position == mode && page > 0) return;
                mode = position;
                reload();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        search.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEARCH
                        || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                    query = search.getText().toString().trim();
                    InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                    imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
                    reload();
                    return true;
                }
                return false;
            }
        });

        setupSites();
        Updater.checkAuto(this);
    }

    @SuppressWarnings("deprecation")
    private void setupSites() {
        ActionBar ab = getActionBar();
        if (ab == null) return;
        final Site[] sites = Site.values();
        String[] names = new String[sites.length];
        int selected = 0;
        for (int i = 0; i < sites.length; i++) {
            names[i] = sites[i].title;
            if (sites[i] == site) selected = i;
        }
        ArrayAdapter<String> a = new ArrayAdapter<>(ab.getThemedContext(), android.R.layout.simple_spinner_item, names);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        ab.setDisplayShowTitleEnabled(false);
        ab.setNavigationMode(ActionBar.NAVIGATION_MODE_LIST);
        ab.setListNavigationCallbacks(a, new ActionBar.OnNavigationListener() {
            @Override
            public boolean onNavigationItemSelected(int position, long itemId) {
                // Первую загрузку запускает спиннер режима, здесь — только смена сайта
                if (sites[position] != site) {
                    site = sites[position];
                    App.get().prefs.setSite(site);
                    if (mode > MODE_HISTORY) {
                        // Папки у сайтов разные (у аниме — «Смотрю», «Запланировано»…)
                        mode = 0;
                        modeSpinner.setSelection(0);
                    }
                    reload();
                    loadFolders();
                }
                return true;
            }
        });
        ab.setSelectedNavigationItem(selected);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // История могла измениться, пока читали
        if (mode == MODE_HISTORY) reload();
        // Могли поменяться настройка обложек или папка тайтла в его карточке
        adapter.notifyDataSetChanged();
        loadFolders();
        invalidateOptionsMenu();
    }

    private void reload() {
        generation++;
        items.clear();
        adapter.notifyDataSetChanged();
        page = 0;
        hasNext = true;
        loading = false;
        if (mode == MODE_HISTORY) {
            List<Title> h = App.get().history.titles(site);
            String q = query.toLowerCase(Locale.getDefault());
            for (Title t : h) {
                if (q.length() == 0 || t.displayName(true).toLowerCase(Locale.getDefault()).contains(q)
                        || (t.name != null && t.name.toLowerCase(Locale.getDefault()).contains(q))) items.add(t);
            }
            hasNext = false;
            page = 1;
            adapter.notifyDataSetChanged();
            status.setText(items.isEmpty() ? "История пуста" : "");
            return;
        }
        loadMore();
    }

    private void loadMore() {
        if (loading || !hasNext) return;
        loading = true;
        final int gen = generation;
        final int next = page + 1;
        final Site s = site;
        final String q = query;
        final String sort = mode < MODE_HISTORY ? SORTS[mode] : null;
        final int folder = mode > MODE_HISTORY ? folders.get(mode - MODE_HISTORY - 1).id : 0;
        if (items.isEmpty()) status.setText("Загрузка…");
        Bg.run(new Bg.Job<LibApi.Paged<Title>>() {
            @Override
            public LibApi.Paged<Title> run() throws Exception {
                LibApi api = App.get().api();
                if (folder != 0) return api.bookmarks(s, api.userId(), folder, q, next);
                return api.catalog(s, q, sort, next);
            }
        }, new Bg.Done<LibApi.Paged<Title>>() {
            @Override
            public void done(LibApi.Paged<Title> result, Exception error) {
                if (gen != generation || isFinishing()) return;
                loading = false;
                if (error != null) {
                    hasNext = false;
                    if (items.isEmpty()) status.setText(Bg.message(error) + "\n\nМеню → Обновить, чтобы повторить");
                    return;
                }
                page = next;
                hasNext = result.hasNext;
                items.addAll(result.items);
                adapter.notifyDataSetChanged();
                status.setText(items.isEmpty() ? "Ничего не найдено" : "");
            }
        });
    }

    /** Папки закладок текущего сайта; без входа в спиннере только каталог и история. */
    private void loadFolders() {
        final int gen = ++folderGeneration;
        final Site s = site;
        if (!App.get().auth().loggedIn()) {
            applyFolders(Collections.<Folder>emptyList());
            return;
        }
        Bg.run(new Bg.Job<List<Folder>>() {
            @Override
            public List<Folder> run() throws Exception {
                LibApi api = App.get().api();
                return api.folders(s, api.userId());
            }
        }, new Bg.Done<List<Folder>>() {
            @Override
            public void done(List<Folder> result, Exception error) {
                if (gen != folderGeneration || isFinishing() || error != null) return;
                applyFolders(result);
            }
        });
    }

    private void applyFolders(List<Folder> fresh) {
        int current = mode > MODE_HISTORY ? folders.get(mode - MODE_HISTORY - 1).id : 0;
        folderById.clear();
        for (Folder f : fresh) folderById.put(f.id, f);
        folders.clear();
        // Пустые папки (свои у других сайтов) только загромождают список
        for (Folder f : fresh) {
            if (f.count > 0) folders.add(f);
        }
        modeLabels.subList(BASE_MODES.length, modeLabels.size()).clear();
        for (Folder f : folders) modeLabels.add(f.label());
        modesAdapter.notifyDataSetChanged();
        if (current == 0) return;
        for (int i = 0; i < folders.size(); i++) {
            if (folders.get(i).id == current) {
                // Счётчики могли поменяться, а папка та же — просто держим выбор на ней
                mode = BASE_MODES.length + i;
                modeSpinner.setSelection(mode);
                return;
            }
        }
        // Папку удалили или вышли из аккаунта
        mode = 0;
        modeSpinner.setSelection(0);
        reload();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, "Обновить");
        String user = App.get().auth().token() != null ? App.get().auth().token().username : null;
        menu.add(0, 2, 0, App.get().auth().loggedIn()
                ? "Аккаунт" + (Text.isEmpty(user) ? "" : ": " + user)
                : "Войти");
        menu.add(0, 3, 0, "Настройки");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case 1:
                reload();
                return true;
            case 2:
                startActivity(new Intent(this, LoginActivity.class));
                return true;
            case 3:
                startActivity(new Intent(this, SettingsActivity.class));
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    private void bindBadge(TextView badge, Title t) {
        Integer changed = App.get().bookmarkChanges.get(t.site.id + ":" + t.slugUrl);
        int status = changed != null ? changed : t.bookmarkStatus;
        Folder f = status == 0 ? null : folderById.get(status);
        if (f == null) {
            badge.setVisibility(View.GONE);
            return;
        }
        badge.setText(f.name);
        badge.setBackgroundColor(Colors.parse(f.color, 0xFFF2994A));
        badge.setTextColor(Colors.parse(f.textColor, Color.BLACK));
        badge.setVisibility(View.VISIBLE);
    }

    private final class TitleAdapter extends BaseAdapter {
        private final LayoutInflater inflater;

        TitleAdapter(Context context) {
            inflater = LayoutInflater.from(context);
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView != null ? convertView : inflater.inflate(R.layout.item_title, parent, false);
            Title t = items.get(position);
            ((TextView) v.findViewById(R.id.name)).setText(t.displayName(App.get().prefs.rusTitles()));
            List<String> info = new ArrayList<>();
            info.add(t.type);
            if (t.rating != null) info.add("★" + t.rating);
            info.add(t.age);
            ((TextView) v.findViewById(R.id.info)).setText(Text.join(" · ", info));
            // Миниатюра всего 100×143 — мыло, но в 10 раз легче полной обложки; выбор в настройках
            boolean full = App.get().prefs.fullCovers() && t.cover != null;
            ((CoverView) v.findViewById(R.id.cover)).load(full ? t.cover : t.thumb, t.site.referer());
            bindBadge((TextView) v.findViewById(R.id.badge), t);
            return v;
        }
    }
}
