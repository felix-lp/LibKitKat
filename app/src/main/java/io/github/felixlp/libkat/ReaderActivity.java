package io.github.felixlp.libkat;

import android.app.ActionBar;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.github.felixlp.libkat.core.Chapter;
import io.github.felixlp.libkat.core.LibApi;
import io.github.felixlp.libkat.core.Page;
import io.github.felixlp.libkat.core.Site;
import io.github.felixlp.libkat.core.Title;

/**
 * Читалка в двух режимах.
 * Лента (манхва): страницы друг под другом, тап сверху/снизу и громкость прокручивают.
 * По страницам (манга): одна страница на экран, тапы по краям, свайпы и громкость листают,
 * щипок и двойной тап увеличивают. Тап по центру в обоих режимах показывает шапку с меню.
 */
public class ReaderActivity extends Activity {
    private static final int PREFETCH = 3;
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
    private int targetWidth;
    private int generation;
    private String mode;
    private int pagedIndex;

    private ListView list;
    private PagedView paged;
    private TextView status;
    private TextView indicator;
    private Button prevBtn;
    private Button nextBtn;
    private Button commentsBtn;
    private final PageAdapter adapter = new PageAdapter();
    /** Комментарии под лентой и на экране «Конец главы» постраничного режима. */
    private CommentsBlock listComments;
    private CommentsBlock endComments;
    private View endScreen;
    private TextView endTitle;
    private Button endNextBtn;
    private boolean showingEnd;

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

        paged = new PagedView(this);
        ((FrameLayout) list.getParent()).addView(paged, 1, new FrameLayout.LayoutParams(
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
        commentsBtn = new Button(this);
        commentsBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openComments();
            }
        });
        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.VERTICAL);
        footer.addView(nextBtn);
        footer.addView(commentsBtn);
        listComments = new CommentsBlock(this);
        footer.addView(listComments.view());
        buildEndScreen();
        list.addHeaderView(prevBtn, null, false);
        list.addFooterView(footer, null, false);
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

    /** Режим тайтла, если его выбирали в меню, иначе из настроек; «авто» смотрит на тип тайтла. */
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
        if (!isPaged()) hideEnd();
        list.setVisibility(isPaged() ? View.GONE : View.VISIBLE);
        paged.setVisibility(isPaged() && !showingEnd ? View.VISIBLE : View.GONE);
        paged.setRtl(RTL.equals(mode));
        applyInline();
    }

    /** Комментарии под главой включены — блок виден, кнопка ведёт на полный экран; выключены — только кнопка. */
    private void applyInline() {
        boolean inline = App.get().prefs.inlineComments();
        listComments.view().setVisibility(inline ? View.VISIBLE : View.GONE);
        endComments.view().setVisibility(inline ? View.VISIBLE : View.GONE);
        commentsBtn.setText(inline ? "Открыть комментарии отдельно" : "Комментарии к главе");
        if (inline) updateIndicator();
    }

    /** Экран после последней страницы в постраничном режиме: следующая глава и комментарии. */
    private void buildEndScreen() {
        float d = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, (int) (48 * d), 0, 0);
        endTitle = new TextView(this);
        endTitle.setTextSize(18);
        endTitle.setGravity(android.view.Gravity.CENTER);
        endTitle.setPadding((int) (12 * d), 0, (int) (12 * d), (int) (12 * d));
        box.addView(endTitle);
        endNextBtn = new Button(this);
        endNextBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                nextChapter();
            }
        });
        box.addView(endNextBtn);
        Button back = new Button(this);
        back.setText("Вернуться к последней странице");
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                turn(-1);
            }
        });
        box.addView(back);
        endComments = new CommentsBlock(this);
        box.addView(endComments.view());
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setBackgroundColor(getResources().getColor(R.color.bg));
        scroll.addView(box);
        endScreen = scroll;
        endScreen.setVisibility(View.GONE);
        ((FrameLayout) list.getParent()).addView(endScreen, 2, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    private void showEnd() {
        showingEnd = true;
        endScreen.setVisibility(View.VISIBLE);
        paged.setVisibility(View.GONE);
        endComments.start();
        if (index < chapters.size()) App.get().history.markRead(site, slug, chapters.get(index).id);
        indicator.setText("Конец главы");
    }

    private void hideEnd() {
        if (!showingEnd) return;
        showingEnd = false;
        endScreen.setVisibility(View.GONE);
        if (isPaged()) paged.setVisibility(View.VISIBLE);
    }

    /** Настройки прямо в читалке: режим тайтла, сервер картинок, комментарии под главой. */
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
                if (!chapters.isEmpty()) loadChapter(index, currentPage());
            }
        }));

        box.addView(header("Комментарии"));
        android.widget.CheckBox inline = new android.widget.CheckBox(this);
        inline.setText("Показывать под главой");
        inline.setChecked(App.get().prefs.inlineComments());
        inline.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                App.get().prefs.sp.edit().putBoolean(Prefs.INLINE_COMMENTS, checked).apply();
                applyInline();
            }
        });
        box.addView(inline);

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
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

    private android.widget.RadioGroup radios(String[] labels, int checked, final Pick pick) {
        android.widget.RadioGroup g = new android.widget.RadioGroup(this);
        for (int i = 0; i < labels.length; i++) {
            android.widget.RadioButton b = new android.widget.RadioButton(this);
            b.setId(1000 + i);
            b.setText(labels[i]);
            g.addView(b);
        }
        if (checked >= 0) g.check(1000 + checked);
        g.setOnCheckedChangeListener(new android.widget.RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.RadioGroup group, int id) {
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
            adapter.notifyDataSetChanged();
            list.setSelection(p + list.getHeaderViewsCount());
        }
        updateIndicator();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, "⚙ Настройки").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        menu.add(0, 2, 0, "Комментарии").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == 1) {
            showReaderSettings();
            return true;
        }
        if (item.getItemId() == 2) {
            openComments();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void openComments() {
        if (index < 0 || index >= chapters.size()) return;
        Chapter c = chapters.get(index);
        Chapter.Branch b = c.branch(branch);
        CommentsActivity.open(this, site, LibApi.POST_CHAPTER, b == null ? c.id : b.id, c.label());
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

    /** @param restorePage страница, на которую встать; -1 — взять из истории, LAST_PAGE — последняя */
    private void loadChapter(final int idx, final int restorePage) {
        index = idx;
        final Chapter c = chapters.get(idx);
        final int gen = ++generation;
        setTitle(c.label());
        pages = Collections.emptyList();
        urls = Collections.emptyList();
        adapter.notifyDataSetChanged();
        paged.bind("chapter:" + gen, 0);
        hideEnd();
        Chapter.Branch cb = c.branch(branch);
        int commentsId = cb == null ? c.id : cb.id;
        listComments.reset(site, LibApi.POST_CHAPTER, commentsId);
        endComments.reset(site, LibApi.POST_CHAPTER, commentsId);
        endTitle.setText("Конец: " + c.label());
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
                    status.setText(Bg.message(error) + "\n\nТап по центру — меню");
                    return;
                }
                pages = result.pages;
                urls = result.urls;
                status.setText(pages.isEmpty() ? "В главе нет страниц" : "");
                adapter.notifyDataSetChanged();
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

    private static final class ChapterData {
        List<Page> pages;
        List<String> urls;
    }

    private void updateButtons() {
        prevBtn.setVisibility(index > 0 ? View.VISIBLE : View.GONE);
        boolean hasNext = index + 1 < chapters.size();
        String label = hasNext ? "Следующая: " + chapters.get(index + 1).label() + " →" : "Это последняя глава";
        for (Button b : new Button[]{nextBtn, endNextBtn}) {
            b.setText(label);
            b.setEnabled(hasNext);
        }
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
        // В ленте комментарии начинают грузиться, когда до них долистали
        if (atEnd && !isPaged() && App.get().prefs.inlineComments()) listComments.start();
        if (showingEnd) {
            indicator.setText("Конец главы");
            return;
        }
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
        String url = urls.get(i);
        int w = pagedWidth(pages.get(i));
        paged.bind(ImageLoader.key(url, w), i + 1);
        App.get().images.load(url, site.referer(), w, paged);
        for (int j = i + 1; j <= i + PREFETCH && j < urls.size(); j++) {
            App.get().images.prefetch(urls.get(j), site.referer());
        }
        updateIndicator();
    }

    private void turn(int delta) {
        if (pages.isEmpty()) return;
        if (showingEnd) {
            // С экрана «Конец главы»: вперёд — следующая глава, назад — последняя страница
            if (delta > 0) {
                nextChapter();
            } else {
                hideEnd();
                showPage(pages.size() - 1);
            }
            return;
        }
        int next = pagedIndex + delta;
        if (next >= 0 && next < pages.size()) {
            showPage(next);
        } else if (next >= pages.size()) {
            if (App.get().prefs.inlineComments()) showEnd();
            else nextChapter();
        } else if (index > 0) {
            Toast.makeText(this, chapters.get(index - 1).label(), Toast.LENGTH_SHORT).show();
            loadChapter(index - 1, LAST_PAGE);
        } else {
            Toast.makeText(this, "Это первая страница", Toast.LENGTH_SHORT).show();
        }
    }

    private void nextChapter() {
        if (index + 1 < chapters.size()) {
            Toast.makeText(this, chapters.get(index + 1).label(), Toast.LENGTH_SHORT).show();
            loadChapter(index + 1, 0);
        } else {
            Toast.makeText(this, "Это последняя глава", Toast.LENGTH_SHORT).show();
        }
    }

    // ---------- Лента ----------

    private void onListTap(MotionEvent e) {
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
