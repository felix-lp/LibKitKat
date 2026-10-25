package io.github.felixlp.libkat;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.felixlp.libkat.core.Bookmark;
import io.github.felixlp.libkat.core.Chapter;
import io.github.felixlp.libkat.core.Episode;
import io.github.felixlp.libkat.core.Folder;
import io.github.felixlp.libkat.core.LibApi;
import io.github.felixlp.libkat.core.Site;
import io.github.felixlp.libkat.core.Text;
import io.github.felixlp.libkat.core.Title;

/** Карточка тайтла: описание и список глав или серий. */
public class TitleActivity extends Activity {
    private Site site;
    private String slug;
    private Title title;
    private List<Chapter> chapters = Collections.emptyList();
    private List<Episode> episodes = Collections.emptyList();
    private Integer branch;
    private final List<Integer> branchIds = new ArrayList<>();
    private Set<Integer> read = new HashSet<>();
    private Bookmark bookmark;
    private List<Folder> folders;

    private View header;
    private TextView status;
    private Button continueBtn;
    private Button bookmarkBtn;
    private RowAdapter adapter;

    public static void open(Activity from, Title t) {
        Intent i = new Intent(from, TitleActivity.class);
        i.putExtra("site", t.site.id);
        i.putExtra("slug", t.slugUrl);
        try {
            i.putExtra("title", t.toJson().toString());
        } catch (JSONException ignored) {
            // Без заготовки шапка просто появится после загрузки
        }
        from.startActivity(i);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        site = Site.byId(getIntent().getIntExtra("site", 1));
        slug = getIntent().getStringExtra("slug");
        try {
            title = Title.parse(new JSONObject(getIntent().getStringExtra("title")), site);
        } catch (Exception e) {
            title = new Title();
            title.site = site;
            title.slugUrl = slug;
            title.name = slug;
        }
        branch = App.get().history.branch(site, slug);
        if (getActionBar() != null) getActionBar().setDisplayHomeAsUpEnabled(true);

        ListView list = new ListView(this);
        header = LayoutInflater.from(this).inflate(R.layout.header_title, list, false);
        list.addHeaderView(header, null, false);
        adapter = new RowAdapter();
        list.setAdapter(adapter);
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                int row = position - 1;
                if (row < 0 || row >= adapter.getCount()) return false;
                openRowComments(row);
                return true;
            }
        });
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                int row = position - 1;
                if (row >= 0 && row < adapter.getCount()) openRow(row);
            }
        });
        setContentView(list);

        status = (TextView) header.findViewById(R.id.status);
        continueBtn = (Button) header.findViewById(R.id.continue_btn);
        bookmarkBtn = (Button) header.findViewById(R.id.bookmark_btn);
        bookmarkBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chooseFolder();
            }
        });
        final TextView summary = (TextView) header.findViewById(R.id.summary);
        summary.setOnClickListener(new View.OnClickListener() {
            boolean expanded;

            @Override
            public void onClick(View v) {
                expanded = !expanded;
                summary.setMaxLines(expanded ? Integer.MAX_VALUE : 5);
            }
        });
        fillHeader();
        load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        read = App.get().history.read(site, slug);
        adapter.notifyDataSetChanged();
        updateContinue();
        updateBookmarkButton();
    }

    @Override
    public boolean onNavigateUp() {
        finish();
        return true;
    }

    private void fillHeader() {
        boolean rus = App.get().prefs.rusTitles();
        setTitle(title.displayName(rus));
        ((TextView) header.findViewById(R.id.name)).setText(title.displayName(rus));
        String alt = title.secondaryName(rus);
        ((TextView) header.findViewById(R.id.alt)).setText(alt == null ? "" : alt);
        List<String> info = new ArrayList<>();
        info.add(title.type);
        info.add(title.year);
        info.add(title.status);
        info.add(title.age);
        if (title.rating != null) info.add("★ " + title.rating);
        ((TextView) header.findViewById(R.id.info)).setText(Text.join("\n", info));
        ((TextView) header.findViewById(R.id.genres)).setText(Text.join(", ", title.genres));
        ((TextView) header.findViewById(R.id.summary)).setText(title.summary == null ? "" : title.summary);
        ((CoverView) header.findViewById(R.id.cover)).load(title.cover != null ? title.cover : title.thumb, site.referer());
    }

    private static final class Loaded {
        Title details;
        String detailsError;
        Bookmark bookmark;
        List<Folder> folders;
        List<Chapter> chapters;
        List<Episode> episodes;
    }

    private void load() {
        status.setText("Загрузка…");
        Bg.run(new Bg.Job<Loaded>() {
            @Override
            public Loaded run() throws Exception {
                LibApi api = App.get().api();
                Loaded l = new Loaded();
                try {
                    l.details = api.details(site, slug);
                } catch (Exception e) {
                    l.detailsError = Bg.message(e);
                }
                if (App.get().auth().loggedIn()) {
                    try {
                        l.bookmark = api.bookmark(site, slug);
                        l.folders = api.folders(site, api.userId());
                    } catch (Exception ignored) {
                        // Без статуса закладки карточка всё равно полезна
                    }
                }
                if (site.anime) l.episodes = api.episodes(slug);
                else l.chapters = api.chapters(site, slug);
                return l;
            }
        }, new Bg.Done<Loaded>() {
            @Override
            public void done(Loaded l, Exception error) {
                if (isFinishing()) return;
                if (error != null) {
                    status.setText(Bg.message(error));
                    return;
                }
                if (l.details != null) {
                    title = l.details;
                    fillHeader();
                }
                App.get().history.touch(title);
                bookmark = l.bookmark;
                if (l.folders != null) folders = l.folders;
                updateBookmarkButton();
                if (l.chapters != null) chapters = l.chapters;
                if (l.episodes != null) episodes = l.episodes;
                App.get().currentTitle = title;
                App.get().currentChapters = chapters;
                setupBranches();
                adapter.notifyDataSetChanged();
                int n = adapter.getCount();
                String s = site.anime ? "Серий: " + n : "Глав: " + n;
                if (n == 0) s = site.anime ? "Серий пока нет" : "Глав пока нет";
                if (l.detailsError != null) s += "\n(описание: " + l.detailsError + ")";
                status.setText(s);
                updateContinue();
            }
        });
    }

    private void setupBranches() {
        Map<Integer, String> teams = new LinkedHashMap<>();
        for (Chapter c : chapters) {
            for (Chapter.Branch b : c.branches) {
                if (b.branchId != null && !teams.containsKey(b.branchId)) teams.put(b.branchId, b.teams);
            }
        }
        Spinner spinner = (Spinner) header.findViewById(R.id.branch);
        if (teams.size() < 2) {
            spinner.setVisibility(View.GONE);
            return;
        }
        branchIds.clear();
        List<String> labels = new ArrayList<>();
        branchIds.add(null);
        labels.add("Перевод: по умолчанию");
        int selected = 0;
        for (Map.Entry<Integer, String> e : teams.entrySet()) {
            if (e.getKey().equals(branch)) selected = branchIds.size();
            branchIds.add(e.getKey());
            labels.add("Перевод: " + (Text.isEmpty(e.getValue()) ? "#" + e.getKey() : e.getValue()));
        }
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(a);
        spinner.setSelection(selected);
        spinner.setVisibility(View.VISIBLE);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                branch = branchIds.get(position);
                App.get().history.setBranch(site, slug, branch);
                adapter.notifyDataSetChanged();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    private void updateContinue() {
        int n = site.anime ? episodes.size() : chapters.size();
        if (n == 0) {
            continueBtn.setVisibility(View.GONE);
            return;
        }
        int last = App.get().history.lastItem(site, slug);
        int index = 0;
        String label = site.anime ? "Смотреть с начала" : "Читать с начала";
        for (int i = 0; i < n; i++) {
            int id = site.anime ? episodes.get(i).id : chapters.get(i).id;
            if (id == last) {
                index = i;
                label = "Продолжить: " + (site.anime ? "эп. " + episodes.get(i).number
                        : "т. " + chapters.get(i).volume + " гл. " + chapters.get(i).number);
                break;
            }
        }
        final int target = index;
        continueBtn.setText(label);
        continueBtn.setVisibility(View.VISIBLE);
        continueBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (site.anime) VideoFlow.play(TitleActivity.this, title, episodes.get(target));
                else openChapter(target);
            }
        });
    }

    private void openRow(int row) {
        if (site.anime) {
            VideoFlow.play(this, title, episodes.get(row));
        } else {
            openChapter(chapters.size() - 1 - row);
        }
    }

    private void openChapter(int index) {
        Chapter.Branch b = chapters.get(index).branch(branch);
        if (b != null && !b.open) {
            Toast.makeText(this, "Глава платная или закрыта переводчиком", Toast.LENGTH_SHORT).show();
            return;
        }
        App.get().currentTitle = title;
        App.get().currentChapters = chapters;
        ReaderActivity.open(this, title, index, branch);
    }

    private void updateBookmarkButton() {
        if (!App.get().auth().loggedIn()) {
            bookmarkBtn.setText("＋ В закладки (нужен вход)");
            return;
        }
        Folder f = bookmark == null ? null : folder(bookmark.status);
        bookmarkBtn.setText(bookmark == null ? "＋ В закладки" : "В списке: " + (f == null ? "#" + bookmark.status : f.name) + " ▾");
    }

    private Folder folder(int id) {
        if (folders == null) return null;
        for (Folder f : folders) {
            if (f.id == id) return f;
        }
        return null;
    }

    private void chooseFolder() {
        if (!App.get().auth().loggedIn()) {
            startActivity(new Intent(this, LoginActivity.class));
            return;
        }
        if (folders == null) {
            Toast.makeText(this, "Загружаю папки…", Toast.LENGTH_SHORT).show();
            Bg.run(new Bg.Job<List<Folder>>() {
                @Override
                public List<Folder> run() throws Exception {
                    LibApi api = App.get().api();
                    return api.folders(site, api.userId());
                }
            }, new Bg.Done<List<Folder>>() {
                @Override
                public void done(List<Folder> result, Exception error) {
                    if (isFinishing()) return;
                    if (error != null) {
                        Toast.makeText(TitleActivity.this, Bg.message(error), Toast.LENGTH_LONG).show();
                        return;
                    }
                    folders = result;
                    chooseFolder();
                }
            });
            return;
        }
        final int n = folders.size();
        String[] labels = new String[bookmark == null ? n : n + 1];
        for (int i = 0; i < n; i++) {
            Folder f = folders.get(i);
            labels[i] = (bookmark != null && bookmark.status == f.id ? "✓ " : "") + f.name;
        }
        if (bookmark != null) labels[n] = "✕ Убрать из закладок";
        new AlertDialog.Builder(this)
                .setTitle("Папка закладок")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which < n) setFolder(folders.get(which).id);
                        else setFolder(0);
                    }
                })
                .show();
    }

    /** @param folderId папка или 0 — убрать из закладок */
    private void setFolder(final int folderId) {
        if (bookmark != null && bookmark.status == folderId) return;
        final Bookmark old = bookmark;
        bookmarkBtn.setEnabled(false);
        Bg.run(new Bg.Job<Bookmark>() {
            @Override
            public Bookmark run() throws Exception {
                LibApi api = App.get().api();
                if (folderId != 0) return api.setBookmark(site, slug, folderId);
                api.deleteBookmark(site, old.id);
                return null;
            }
        }, new Bg.Done<Bookmark>() {
            @Override
            public void done(Bookmark result, Exception error) {
                if (isFinishing()) return;
                bookmarkBtn.setEnabled(true);
                if (error != null) {
                    Toast.makeText(TitleActivity.this, Bg.message(error), Toast.LENGTH_LONG).show();
                    return;
                }
                bookmark = result;
                App.get().bookmarkChanges.put(site.id + ":" + slug, folderId);
                updateBookmarkButton();
            }
        });
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, "Комментарии").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == 1) {
            CommentsActivity.open(this, site, site.anime ? LibApi.POST_ANIME : LibApi.POST_MANGA, title.id,
                    title.displayName(App.get().prefs.rusTitles()));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /** Долгое нажатие на главу или серию — её комментарии. */
    private void openRowComments(int row) {
        if (site.anime) {
            Episode e = episodes.get(row);
            CommentsActivity.open(this, site, LibApi.POST_EPISODE, e.id, e.label());
        } else {
            Chapter c = chapters.get(chapters.size() - 1 - row);
            Chapter.Branch b = c.branch(branch);
            CommentsActivity.open(this, site, LibApi.POST_CHAPTER, b == null ? c.id : b.id, c.label());
        }
    }

    /** Главы — от новых к старым (как на сайте), серии — по порядку. */
    private final class RowAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return site.anime ? episodes.size() : chapters.size();
        }

        @Override
        public Object getItem(int position) {
            return null;
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView != null ? convertView
                    : LayoutInflater.from(TitleActivity.this).inflate(R.layout.item_chapter, parent, false);
            TextView t = (TextView) v.findViewById(R.id.title);
            TextView sub = (TextView) v.findViewById(R.id.sub);
            int id;
            if (site.anime) {
                Episode e = episodes.get(position);
                id = e.id;
                t.setText(e.label());
                sub.setText(e.status == null ? "" : e.status);
            } else {
                Chapter c = chapters.get(chapters.size() - 1 - position);
                id = c.id;
                Chapter.Branch b = c.branch(branch);
                boolean locked = b != null && !b.open;
                t.setText((locked ? "🔒 " : "") + c.label());
                List<String> parts = new ArrayList<>();
                if (b != null) {
                    parts.add(b.teams);
                    if (b.createdAt != null && b.createdAt.length() >= 10) parts.add(b.createdAt.substring(0, 10));
                }
                if (c.branches.size() > 1) parts.add("переводов: " + c.branches.size());
                sub.setText(Text.join(" · ", parts));
            }
            boolean done = read.contains(id);
            t.setTextColor(done ? Color.GRAY : Color.WHITE);
            return v;
        }
    }
}
