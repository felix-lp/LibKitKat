package io.github.felixlp.libkat;

import android.app.ActionBar;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.github.felixlp.libkat.core.Chapter;
import io.github.felixlp.libkat.core.LibApi;
import io.github.felixlp.libkat.core.Page;
import io.github.felixlp.libkat.core.Site;
import io.github.felixlp.libkat.core.Title;

/**
 * Читалка в двух режимах.
 * Лента (манхва): страницы друг под другом, тап сверху/снизу и громкость прокручивают.
 * По страницам (манга): одна страница на экран, тапы по краям, свайпы и громкость листают,
 * щипок и двойной тап увеличивают. Тап по центру в обоих режимах показывает шапку с ⚙.
 * Комментарии у глав постраничные: в ленте — плашка под каждой страницей, по страницам — кнопка 💬.
 */
public class ReaderActivity extends Activity {
    /** Сколько следующих страниц заранее скачивать на диск. */
    private static final int PREFETCH = 5;
    private static final String WEBTOON = "webtoon";
    private static final String LTR = "ltr";
    private static final String RTL = "rtl";
    /** restorePage: встать на последнюю страницу (листаем назад в предыдущую главу). */
    private static final int LAST_PAGE = Integer.MAX_VALUE;

    private Site site;
    private String slug;
    private String titleType;
    private Integer branch;
    private List<Chapter> chapters = Collections.emptyList();
    private int index;
    private List<Page> pages = Collections.emptyList();
    private List<String> urls = Collections.emptyList();
    /** id главы в выбранном переводе — к нему привязаны комментарии страниц. */
    private int commentsChapterId;
    private int targetWidth;
    private int generation;
    private String mode;
    private int pagedIndex;

    /** Следующая глава, подтянутая заранее, пока дочитываем текущую. */
    private ChapterData nextData;
    private int nextDataIndex = -1;
    private boolean nextLoading;

    private ListView list;
    private PagedView paged;
    private TextView status;
    private TextView indicator;
    private Button prevBtn;
    private Button nextBtn;
    private final PageAdapter adapter = new PageAdapter();
    /** Раскрытые комментарии под страницами ленты: номер страницы → блок. */
    private final Map<Integer, CommentsBlock> openComments = new HashMap<>();
    private Button pagedCommentsBtn;
    private View pagedPanel;
    private TextView pagedPanelTitle;
    private CommentsBlock pagedComments;

    public static void open(Activity from, Title title, int index, Integer branch) {
        Intent i = new Intent(from, ReaderActivity.class);
        i.putExtra("site", title.site.id);
        i.putExtra("slug", title.slugUrl);
        i.putExtra("type", title.type);
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
        titleType = getIntent().getStringExtra("type");
        index = getIntent().getIntExtra("index", 0);
        int b = getIntent().getIntExtra("branch", -1);
        branch = b < 0 ? null : b;
        targetWidth = Math.min(getResources().getDisplayMetrics().widthPixels, 1280);

        list = (ListView) findViewById(R.id.list);
        status = (TextView) findViewById(R.id.status);
        indicator = (TextView) findViewById(R.id.indicator);
        FrameLayout root = (FrameLayout) list.getParent();

        paged = new PagedView(this);
        root.addView(paged, 1, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        paged.setListener(new PagedView.Listener() {
            @Override
            public void onTurn(int delta) {
                turn(delta);
            }

            @Override
            public void onCenterTap() {
                toggleUi();
            }

            @Override
            public void onRetry() {
                showPage(pagedIndex);
            }
        });
        buildPagedComments(root);

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
                nextChapter();
            }
        });
        list.addHeaderView(prevBtn, null, false);
        list.addFooterView(nextBtn, null, false);
        list.setAdapter(adapter);

        final GestureDetector gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                onListTap(e);
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
        mode = resolveMode();
        applyMode();

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

    // ---------- Режим и настройки ----------

    /** Режим тайтла, если его выбирали в ⚙, иначе из настроек; «авто» смотрит на тип тайтла. */
    private String resolveMode() {
        String m = App.get().history.readerMode(site, slug);
        if (m == null) m = App.get().prefs.readerMode();
        if (WEBTOON.equals(m) || LTR.equals(m) || RTL.equals(m)) return m;
        String type = titleType == null ? "" : titleType;
        if (type.contains("Манхва") || type.contains("Маньхуа")) return WEBTOON;
        if (type.equals("Манга")) return RTL;
        return LTR;
    }

    private boolean isPaged() {
        return !WEBTOON.equals(mode);
    }

    private void applyMode() {
        list.setVisibility(isPaged() ? View.GONE : View.VISIBLE);
        paged.setVisibility(isPaged() ? View.VISIBLE : View.GONE);
        paged.setRtl(RTL.equals(mode));
        applyInline();
    }

    /** Плашки комментариев под страницами ленты и кнопка 💬 в постраничном режиме. */
    private void applyInline() {
        boolean inline = App.get().prefs.inlineComments();
        pagedCommentsBtn.setVisibility(isPaged() && inline && !pages.isEmpty() ? View.VISIBLE : View.GONE);
        if (!inline || !isPaged()) pagedPanel.setVisibility(View.GONE);
        adapter.notifyDataSetChanged();
    }

    private void switchMode(String newMode) {
        if (newMode.equals(mode)) return;
        int p = currentPage();
        mode = newMode;
        App.get().history.setReaderMode(site, slug, newMode);
        applyMode();
        if (pages.isEmpty()) return;
        if (isPaged()) {
            showPage(p);
        } else {
            list.setSelection(p + list.getHeaderViewsCount());
        }
        updateIndicator();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, "⚙ Настройки").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        menu.add(0, 2, 0, "Комментарии к странице").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == 1) {
            showReaderSettings();
            return true;
        }
        if (item.getItemId() == 2) {
            if (!pages.isEmpty()) {
                int p = currentPage() + 1;
                CommentsActivity.open(this, site, LibApi.POST_CHAPTER, commentsChapterId, p,
                        chapters.get(index).label() + ", стр. " + p);
            }
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /** Настройки прямо в читалке: режим тайтла, сервер картинок, комментарии под страницами. */
    private void showReaderSettings() {
        float d = getResources().getDisplayMetrics().density;
        int pad = (int) (16 * d);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, pad / 2);

        final String[] modes = {WEBTOON, LTR, RTL};
        String[] modeLabels = {"Лента (манхва, вебтуны)", "По страницам: слева направо", "По страницам: справа налево (манга)"};
        box.addView(header("Режим для этого тайтла"));
        box.addView(radios(modeLabels, indexOf(modes, mode), new Pick() {
            @Override
            public void pick(int i) {
                switchMode(modes[i]);
            }
        }));

        final String[] servers = {"compress", "main", "secondary"};
        String[] serverLabels = {"Сжатый (быстрее, JPEG)", "Первый (оригинал)", "Второй (оригинал)"};
        box.addView(header("Сервер изображений"));
        box.addView(radios(serverLabels, indexOf(servers, App.get().prefs.imageServer()), new Pick() {
            @Override
            public void pick(int i) {
                App.get().prefs.sp.edit().putString(Prefs.IMAGE_SERVER, servers[i]).apply();
                // Адреса страниц зависят от сервера — перечитываем главу с той же страницы
                nextDataIndex = -1;
                if (!chapters.isEmpty()) loadChapter(index, currentPage());
            }
        }));

        box.addView(header("Комментарии"));
        CheckBox inline = new CheckBox(this);
        inline.setText("Под страницами (выключено — только в меню)");
        inline.setChecked(App.get().prefs.inlineComments());
        inline.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                App.get().prefs.sp.edit().putBoolean(Prefs.INLINE_COMMENTS, checked).apply();
                applyInline();
            }
        });
        box.addView(inline);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);
        new AlertDialog.Builder(this).setTitle("Настройки чтения").setView(scroll)
                .setPositiveButton("Готово", null).show();
    }

    private interface Pick {
        void pick(int index);
    }

    private TextView header(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(getResources().getColor(R.color.accent));
        t.setPadding(0, (int) (12 * getResources().getDisplayMetrics().density), 0, 0);
        return t;
    }

    private RadioGroup radios(String[] labels, int checked, final Pick pick) {
        RadioGroup g = new RadioGroup(this);
        for (int i = 0; i < labels.length; i++) {
            RadioButton b = new RadioButton(this);
            b.setId(1000 + i);
            b.setText(labels[i]);
            g.addView(b);
        }
        if (checked >= 0) g.check(1000 + checked);
        g.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int id) {
                pick.pick(id - 1000);
            }
        });
        return g;
    }

    private static int indexOf(String[] values, String v) {
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(v)) return i;
        }
        return -1;
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
        int p = isPaged() ? pagedIndex : list.getFirstVisiblePosition() - list.getHeaderViewsCount();
        return Math.max(0, Math.min(p, pages.size() - 1));
    }

    // ---------- Загрузка глав ----------

    private static final class ChapterData {
        List<Page> pages;
        List<String> urls;
        String server;
    }

    private Integer branchIdOf(Chapter c) {
        Chapter.Branch b = c.branch(branch);
        return b == null ? null : b.branchId;
    }

    private ChapterData fetchChapter(Chapter c, Integer branchId, String server) throws Exception {
        LibApi api = App.get().api();
        ChapterData d = new ChapterData();
        d.pages = api.pages(site, slug, c, branchId);
        d.urls = new ArrayList<>();
        for (Page p : d.pages) d.urls.add(api.pageUrl(site, p, server));
        d.server = server;
        return d;
    }

    /** @param restorePage страница, на которую встать; -1 — взять из истории, LAST_PAGE — последняя */
    private void loadChapter(final int idx, final int restorePage) {
        index = idx;
        final Chapter c = chapters.get(idx);
        final int gen = ++generation;
        setTitle(c.label());
        pages = Collections.emptyList();
        urls = Collections.emptyList();
        openComments.clear();
        pagedPanel.setVisibility(View.GONE);
        adapter.notifyDataSetChanged();
        paged.bind("chapter:" + gen, 0);
        Chapter.Branch cb = c.branch(branch);
        commentsChapterId = cb == null ? c.id : cb.id;
        updateButtons();
        applyInline();
        status.setText("Загрузка главы…");
        final Integer branchId = branchIdOf(c);
        final String server = App.get().prefs.imageServer();
        // Если следующую главу уже подтянули, пока дочитывали прошлую, — открываем сразу
        final ChapterData cached = nextDataIndex == idx && nextData != null && server.equals(nextData.server)
                ? nextData : null;
        Bg.run(new Bg.Job<ChapterData>() {
            @Override
            public ChapterData run() throws Exception {
                return cached != null ? cached : fetchChapter(c, branchId, server);
            }
        }, new Bg.Done<ChapterData>() {
            @Override
            public void done(ChapterData result, Exception error) {
                if (gen != generation || isFinishing()) return;
                if (error != null) {
                    status.setText(Bg.message(error) + "\n\nТап по центру — меню");
                    return;
                }
                pages = result.pages;
                urls = result.urls;
                status.setText(pages.isEmpty() ? "В главе нет страниц" : "");
                adapter.notifyDataSetChanged();
                applyInline();
                if (pages.isEmpty()) return;
                int start = restorePage == LAST_PAGE ? pages.size() - 1
                        : restorePage >= 0 ? restorePage : App.get().history.lastPage(site, slug, c.id);
                start = Math.max(0, Math.min(start, pages.size() - 1));
                if (isPaged()) showPage(start);
                else list.setSelection(start + list.getHeaderViewsCount());
                App.get().history.setLast(site, slug, c.id, start);
                updateIndicator();
            }
        });
    }

    /** Подтянуть список страниц следующей главы и скачать её начало, пока дочитываем текущую. */
    private void prefetchNextChapter() {
        final int ni = index + 1;
        if (ni >= chapters.size() || nextDataIndex == ni || nextLoading) return;
        nextLoading = true;
        final Chapter c = chapters.get(ni);
        final Integer branchId = branchIdOf(c);
        final String server = App.get().prefs.imageServer();
        Bg.run(new Bg.Job<ChapterData>() {
            @Override
            public ChapterData run() throws Exception {
                return fetchChapter(c, branchId, server);
            }
        }, new Bg.Done<ChapterData>() {
            @Override
            public void done(ChapterData d, Exception error) {
                nextLoading = false;
                if (error != null || isFinishing()) return;
                nextData = d;
                nextDataIndex = ni;
                for (int i = 0; i < 3 && i < d.urls.size(); i++) App.get().images.prefetch(d.urls.get(i), site.referer());
            }
        });
    }

    private void nextChapter() {
        if (index + 1 < chapters.size()) {
            Toast.makeText(this, chapters.get(index + 1).label(), Toast.LENGTH_SHORT).show();
            loadChapter(index + 1, 0);
        } else {
            Toast.makeText(this, "Это последняя глава", Toast.LENGTH_SHORT).show();
        }
    }

    private void updateButtons() {
        prevBtn.setVisibility(index > 0 ? View.VISIBLE : View.GONE);
        boolean hasNext = index + 1 < chapters.size();
        nextBtn.setText(hasNext ? "Следующая: " + chapters.get(index + 1).label() + " →" : "Это последняя глава");
        nextBtn.setEnabled(hasNext);
    }

    private void updateIndicator() {
        if (pages.isEmpty()) {
            indicator.setText("");
            return;
        }
        boolean atEnd;
        if (isPaged()) {
            atEnd = pagedIndex >= pages.size() - 1;
        } else {
            int footer = list.getHeaderViewsCount() + pages.size();
            atEnd = list.getLastVisiblePosition() >= footer;
        }
        if (atEnd && index < chapters.size()) App.get().history.markRead(site, slug, chapters.get(index).id);
        indicator.setText((currentPage() + 1) + " / " + pages.size());
    }

    // ---------- Постраничный режим ----------

    /** Ширина декодирования: вдвое больше вписанной страницы — чтобы зум не мылил, но не шире 2048. */
    private int pagedWidth(Page p) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int w = paged.getWidth() > 0 ? paged.getWidth() : dm.widthPixels;
        int h = paged.getHeight() > 0 ? paged.getHeight() : dm.heightPixels;
        int fit = (int) Math.min(w, h / p.ratio());
        return Math.max(1, Math.min(2048, fit * 2));
    }

    private void showPage(int i) {
        if (i < 0 || i >= urls.size()) return;
        pagedIndex = i;
        pagedPanel.setVisibility(View.GONE);
        String url = urls.get(i);
        int w = pagedWidth(pages.get(i));
        paged.bind(ImageLoader.key(url, w), i + 1);
        App.get().images.load(url, site.referer(), w, paged);
        // Соседние страницы — сразу в память, чтобы перелистывание было мгновенным, дальние — на диск
        if (i + 1 < urls.size()) App.get().images.preload(urls.get(i + 1), site.referer(), pagedWidth(pages.get(i + 1)));
        if (i > 0) App.get().images.preload(urls.get(i - 1), site.referer(), pagedWidth(pages.get(i - 1)));
        for (int j = i + 2; j <= i + PREFETCH && j < urls.size(); j++) {
            App.get().images.prefetch(urls.get(j), site.referer());
        }
        if (i >= urls.size() - 3) prefetchNextChapter();
        updateIndicator();
    }

    private void turn(int delta) {
        if (pages.isEmpty()) return;
        int next = pagedIndex + delta;
        if (next >= 0 && next < pages.size()) {
            showPage(next);
        } else if (next >= pages.size()) {
            nextChapter();
        } else if (index > 0) {
            Toast.makeText(this, chapters.get(index - 1).label(), Toast.LENGTH_SHORT).show();
            loadChapter(index - 1, LAST_PAGE);
        } else {
            Toast.makeText(this, "Это первая страница", Toast.LENGTH_SHORT).show();
        }
    }

    /** Кнопка 💬 и выезжающая снизу панель с комментариями текущей страницы. */
    private void buildPagedComments(FrameLayout root) {
        float d = getResources().getDisplayMetrics().density;
        pagedCommentsBtn = new Button(this);
        pagedCommentsBtn.setText("💬");
        pagedCommentsBtn.setTextColor(Color.WHITE);
        pagedCommentsBtn.setBackgroundColor(0x88000000);
        pagedCommentsBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                togglePagedComments();
            }
        });
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams((int) (56 * d), (int) (44 * d),
                Gravity.BOTTOM | Gravity.LEFT);
        root.addView(pagedCommentsBtn, lp);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(getResources().getColor(R.color.bg));
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        pagedPanelTitle = new TextView(this);
        pagedPanelTitle.setTextSize(16);
        pagedPanelTitle.setPadding((int) (12 * d), 0, 0, 0);
        head.addView(pagedPanelTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button close = new Button(this);
        close.setText("✕");
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pagedPanel.setVisibility(View.GONE);
            }
        });
        head.addView(close);
        panel.addView(head);
        pagedComments = new CommentsBlock(this);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(pagedComments.view());
        panel.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        pagedPanel = panel;
        pagedPanel.setVisibility(View.GONE);
        int h = (int) (getResources().getDisplayMetrics().heightPixels * 0.6f);
        root.addView(pagedPanel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h, Gravity.BOTTOM));
    }

    private void togglePagedComments() {
        if (pagedPanel.getVisibility() == View.VISIBLE) {
            pagedPanel.setVisibility(View.GONE);
            return;
        }
        if (pages.isEmpty()) return;
        int p = pagedIndex + 1;
        pagedPanelTitle.setText("Комментарии к странице " + p);
        pagedComments.reset(site, LibApi.POST_CHAPTER, commentsChapterId, p, "");
        pagedComments.start();
        pagedPanel.setVisibility(View.VISIBLE);
    }

    // ---------- Лента ----------

    private void onListTap(MotionEvent e) {
        int pos = list.pointToPosition((int) e.getX(), (int) e.getY());
        if (pos != ListView.INVALID_POSITION) {
            View child = list.getChildAt(pos - list.getFirstVisiblePosition());
            if (child instanceof PageItem) {
                PageItem item = (PageItem) child;
                // Тап по комментариям под страницей не листает
                if (e.getY() - child.getTop() > item.page.getBottom()) return;
                if (item.page.failed()) {
                    bindItem(item, item.position);
                    return;
                }
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
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            int d = keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ? 1 : -1;
            if (isPaged()) turn(d);
            else scroll(d);
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

    /** Элемент ленты: страница, под ней плашка «Комментарии к странице» и место для раскрытых комментариев. */
    private final class PageItem extends LinearLayout {
        final PageView page;
        final TextView bar;
        final FrameLayout slot;
        int position;

        PageItem() {
            super(ReaderActivity.this);
            setOrientation(VERTICAL);
            float d = getResources().getDisplayMetrics().density;
            page = new PageView(ReaderActivity.this);
            addView(page);
            bar = new TextView(ReaderActivity.this);
            bar.setTextColor(getResources().getColor(R.color.text_dim));
            bar.setTextSize(13);
            bar.setPadding((int) (12 * d), (int) (6 * d), (int) (12 * d), (int) (6 * d));
            bar.setBackgroundColor(0xFF1C1C1C);
            bar.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    toggleListComments(position);
                }
            });
            addView(bar);
            slot = new FrameLayout(ReaderActivity.this);
            addView(slot);
        }
    }

    private void toggleListComments(int position) {
        if (openComments.remove(position) == null) {
            CommentsBlock block = new CommentsBlock(this);
            block.reset(site, LibApi.POST_CHAPTER, commentsChapterId, position + 1, "");
            block.start();
            openComments.put(position, block);
        }
        adapter.notifyDataSetChanged();
    }

    private void bindItem(PageItem item, int position) {
        item.position = position;
        String url = urls.get(position);
        item.page.bind(ImageLoader.key(url, targetWidth), position + 1, pages.get(position).ratio());
        App.get().images.load(url, site.referer(), targetWidth, item.page);
        // Следующую страницу — сразу в память, дальние — на диск
        if (position + 1 < urls.size()) App.get().images.preload(urls.get(position + 1), site.referer(), targetWidth);
        for (int i = position + 2; i <= position + PREFETCH && i < urls.size(); i++) {
            App.get().images.prefetch(urls.get(i), site.referer());
        }
        if (position >= urls.size() - 3) prefetchNextChapter();

        boolean inline = App.get().prefs.inlineComments();
        CommentsBlock block = openComments.get(position);
        item.bar.setVisibility(inline ? View.VISIBLE : View.GONE);
        item.bar.setText("💬 Комментарии к странице " + (position + 1) + (block != null ? "  ▴" : "  ▾"));
        item.slot.removeAllViews();
        if (inline && block != null) {
            View v = block.view();
            if (v.getParent() != null) ((ViewGroup) v.getParent()).removeView(v);
            item.slot.addView(v);
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
            PageItem v = convertView instanceof PageItem ? (PageItem) convertView : new PageItem();
            bindItem(v, position);
            return v;
        }
    }
}
