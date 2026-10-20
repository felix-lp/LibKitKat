package io.github.felixlp.libkat;

import android.app.ActionBar;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
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
import java.util.List;
import java.util.Locale;

import io.github.felixlp.libkat.core.LibApi;
import io.github.felixlp.libkat.core.Site;
import io.github.felixlp.libkat.core.Text;
import io.github.felixlp.libkat.core.Title;

public class MainActivity extends Activity {
    private static final String[] MODES = {
            "Популярное", "По рейтингу", "По просмотрам", "Новинки", "Обновлённые",
            "История", "Читаю", "В планах", "Брошено", "Прочитано", "Любимые"
    };
    private static final String[] SORTS = {
            LibApi.SORT_POPULAR, LibApi.SORT_RATING, LibApi.SORT_VIEWS, LibApi.SORT_NEW, LibApi.SORT_UPDATED
    };
    private static final int MODE_HISTORY = 5;

    private final List<Title> items = new ArrayList<>();
    private Site site;
    private int mode;
    private String query = "";
    private int page;
    private boolean hasNext;
    private boolean loading;
    /** Номер запроса: ответы на устаревшие запросы игнорируются. */
    private int generation;

    private EditText search;
    private TextView status;
    private TitleAdapter adapter;

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

        Spinner modeSpinner = (Spinner) findViewById(R.id.mode);
        ArrayAdapter<String> modes = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, MODES);
        modes.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        modeSpinner.setAdapter(modes);
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
                    reload();
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
        if (mode > MODE_HISTORY && !App.get().auth().loggedIn()) {
            hasNext = false;
            page = 1;
            status.setText("Списки закладок доступны после входа в аккаунт\n(меню → Аккаунт)");
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
        final int list = mode > MODE_HISTORY ? mode - MODE_HISTORY : 0;
        if (items.isEmpty()) status.setText("Загрузка…");
        Bg.run(new Bg.Job<LibApi.Paged<Title>>() {
            @Override
            public LibApi.Paged<Title> run() throws Exception {
                return App.get().api().catalog(s, q, sort, list, next);
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
            ((CoverView) v.findViewById(R.id.cover)).load(t.thumb, t.site.referer());
            return v;
        }
    }
}
