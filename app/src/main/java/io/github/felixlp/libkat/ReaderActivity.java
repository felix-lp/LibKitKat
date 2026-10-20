package io.github.felixlp.libkat;

import android.app.ActionBar;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.github.felixlp.libkat.core.Chapter;
import io.github.felixlp.libkat.core.LibApi;
import io.github.felixlp.libkat.core.Page;
import io.github.felixlp.libkat.core.Site;
import io.github.felixlp.libkat.core.Title;

/**
 * Читалка-лента: страницы друг под другом (подходит и для манги, и для вебтунов).
 * Тап сверху/снизу и клавиши громкости листают, тап по центру показывает шапку.
 */
public class ReaderActivity extends Activity {
    private static final int PREFETCH = 3;

    private Site site;
    private String slug;
    private Integer branch;
    private List<Chapter> chapters = Collections.emptyList();
    private int index;
    private List<Page> pages = Collections.emptyList();
    private List<String> urls = Collections.emptyList();
    private int targetWidth;
    private int generation;

    private ListView list;
    private TextView status;
    private TextView indicator;
    private Button prevBtn;
    private Button nextBtn;
    private final PageAdapter adapter = new PageAdapter();

    public static void open(Activity from, Title title, int index, Integer branch) {
        Intent i = new Intent(from, ReaderActivity.class);
        i.putExtra("site", title.site.id);
        i.putExtra("slug", title.slugUrl);
        i.putExtra("index", index);
        i.putExtra("branch", branch == null ? -1 : branch);
        from.startActivity(i);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reader);
        site = Site.byId(getIntent().getIntExtra("site", 1));
        slug = getIntent().getStringExtra("slug");
        index = getIntent().getIntExtra("index", 0);
        int b = getIntent().getIntExtra("branch", -1);
        branch = b < 0 ? null : b;
        targetWidth = Math.min(getResources().getDisplayMetrics().widthPixels, 1280);

        list = (ListView) findViewById(R.id.list);
        status = (TextView) findViewById(R.id.status);
        indicator = (TextView) findViewById(R.id.indicator);

        prevBtn = new Button(this);
        prevBtn.setText("← Предыдущая глава");
        prevBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (index > 0) loadChapter(index - 1, 0);
            }
        });
        nextBtn = new Button(this);
        nextBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (index + 1 < chapters.size()) loadChapter(index + 1, 0);
            }
        });
        list.addHeaderView(prevBtn, null, false);
        list.addFooterView(nextBtn, null, false);
        list.setAdapter(adapter);

        final GestureDetector gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                onTap(e);
                return true;
            }
        });
        list.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                gestures.onTouchEvent(event);
                return false;
            }
        });
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {
            }

            @Override
            public void onScroll(AbsListView view, int first, int visible, int total) {
                updateIndicator();
            }
        });

        ActionBar ab = getActionBar();
        if (ab != null) {
            ab.setDisplayHomeAsUpEnabled(true);
            ab.hide();
        }

        final App app = App.get();
        if (app.currentTitle != null && slug.equals(app.currentTitle.slugUrl) && app.currentChapters != null
                && !app.currentChapters.isEmpty()) {
            chapters = app.currentChapters;
            loadChapter(index, -1);
        } else {
            // Процесс могли убить, пока читалка была в фоне — список глав запрашиваем заново
            status.setText("Загрузка списка глав…");
            Bg.run(new Bg.Job<List<Chapter>>() {
                @Override
                public List<Chapter> run() throws Exception {
                    return App.get().api().chapters(site, slug);
                }
            }, new Bg.Done<List<Chapter>>() {
                @Override
                public void done(List<Chapter> result, Exception error) {
                    if (isFinishing()) return;
                    if (error != null) {
                        status.setText(Bg.message(error));
                        return;
                    }
                    chapters = result;
                    if (index >= chapters.size()) index = chapters.size() - 1;
                    if (index >= 0) loadChapter(index, -1);
                    else status.setText("Глав нет");
                }
            });
        }
    }

    @Override
    public boolean onNavigateUp() {
        finish();
        return true;
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (index >= 0 && index < chapters.size() && !pages.isEmpty()) {
            App.get().history.setLast(site, slug, chapters.get(index).id, currentPage());
        }
    }

    private int currentPage() {
        int p = list.getFirstVisiblePosition() - list.getHeaderViewsCount();
        return Math.max(0, Math.min(p, pages.size() - 1));
    }

    /** @param restorePage страница, на которую встать; -1 — взять из истории */
    private void loadChapter(final int idx, final int restorePage) {
        index = idx;
        final Chapter c = chapters.get(idx);
        final int gen = ++generation;
        setTitle(c.label());
        pages = Collections.emptyList();
        urls = Collections.emptyList();
        adapter.notifyDataSetChanged();
        updateButtons();
        status.setText("Загрузка главы…");
        Chapter.Branch b = c.branch(branch);
        final Integer branchId = b == null ? null : b.branchId;
        final String server = App.get().prefs.imageServer();
        Bg.run(new Bg.Job<ChapterData>() {
            @Override
            public ChapterData run() throws Exception {
                LibApi api = App.get().api();
                ChapterData d = new ChapterData();
                d.pages = api.pages(site, slug, c, branchId);
                d.urls = new ArrayList<>();
                for (Page p : d.pages) d.urls.add(api.pageUrl(site, p, server));
                return d;
            }
        }, new Bg.Done<ChapterData>() {
            @Override
            public void done(ChapterData result, Exception error) {
                if (gen != generation || isFinishing()) return;
                if (error != null) {
                    status.setText(Bg.message(error) + "\n\nТап по центру — меню, кнопки — соседние главы");
                    return;
                }
                pages = result.pages;
                urls = result.urls;
                status.setText(pages.isEmpty() ? "В главе нет страниц" : "");
                adapter.notifyDataSetChanged();
                int start = restorePage >= 0 ? restorePage : App.get().history.lastPage(site, slug, c.id);
                list.setSelection(Math.min(start, Math.max(0, pages.size() - 1)) + list.getHeaderViewsCount());
                App.get().history.setLast(site, slug, c.id, start);
                updateIndicator();
            }
        });
    }

    private static final class ChapterData {
        List<Page> pages;
        List<String> urls;
    }

    private void updateButtons() {
        prevBtn.setVisibility(index > 0 ? View.VISIBLE : View.GONE);
        if (index + 1 < chapters.size()) {
            nextBtn.setText("Следующая: " + chapters.get(index + 1).label() + " →");
            nextBtn.setEnabled(true);
        } else {
            nextBtn.setText("Это последняя глава");
            nextBtn.setEnabled(false);
        }
    }

    private void updateIndicator() {
        if (pages.isEmpty()) {
            indicator.setText("");
            return;
        }
        int first = list.getFirstVisiblePosition();
        int last = list.getLastVisiblePosition();
        int footer = list.getHeaderViewsCount() + pages.size();
        if (last >= footer && index < chapters.size()) {
            App.get().history.markRead(site, slug, chapters.get(index).id);
        }
        indicator.setText((currentPage() + 1) + " / " + pages.size());
        if (first < 0) indicator.setText("");
    }

    private void onTap(MotionEvent e) {
        int pos = list.pointToPosition((int) e.getX(), (int) e.getY());
        if (pos != ListView.INVALID_POSITION) {
            View child = list.getChildAt(pos - list.getFirstVisiblePosition());
            if (child instanceof PageView && ((PageView) child).failed()) {
                int p = pos - list.getHeaderViewsCount();
                if (p >= 0 && p < urls.size()) bindPage((PageView) child, p);
                return;
            }
        }
        float h = list.getHeight();
        if (e.getY() < h * 0.3f) scroll(-1);
        else if (e.getY() > h * 0.7f) scroll(1);
        else toggleUi();
    }

    private void scroll(int direction) {
        list.smoothScrollBy(direction * (int) (list.getHeight() * 0.85f), 250);
    }

    private void toggleUi() {
        ActionBar ab = getActionBar();
        if (ab == null) return;
        if (ab.isShowing()) ab.hide();
        else ab.show();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            scroll(1);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            scroll(-1);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        // Без этого система пищит и показывает ползунок громкости
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) return true;
        return super.onKeyUp(keyCode, event);
    }

    private void bindPage(PageView v, int position) {
        String url = urls.get(position);
        v.bind(ImageLoader.key(url, targetWidth), position + 1, pages.get(position).ratio());
        App.get().images.load(url, site.referer(), targetWidth, v);
        for (int i = position + 1; i <= position + PREFETCH && i < urls.size(); i++) {
            App.get().images.prefetch(urls.get(i), site.referer());
        }
    }

    private final class PageAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return urls.size();
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
            PageView v = convertView instanceof PageView ? (PageView) convertView : new PageView(ReaderActivity.this);
            bindPage(v, position);
            return v;
        }
    }
}
