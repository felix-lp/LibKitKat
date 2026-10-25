package io.github.felixlp.libkat;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.Html;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import io.github.felixlp.libkat.core.Comment;
import io.github.felixlp.libkat.core.LibApi;
import io.github.felixlp.libkat.core.Site;

/** Комментарии к тайтлу, главе или серии: ветки с ответами, подгрузка при прокрутке. */
public class CommentsActivity extends Activity {
    private final List<Comment> items = new ArrayList<>();
    private Site site;
    private String postType;
    private int postId;
    private int page;
    private boolean hasNext = true;
    private boolean loading;

    private TextView status;
    private CommentAdapter adapter;

    static void open(Activity from, Site site, String postType, int postId, String title) {
        Intent i = new Intent(from, CommentsActivity.class);
        i.putExtra("site", site.id);
        i.putExtra("type", postType);
        i.putExtra("id", postId);
        i.putExtra("title", title);
        from.startActivity(i);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        site = Site.byId(getIntent().getIntExtra("site", 1));
        postType = getIntent().getStringExtra("type");
        postId = getIntent().getIntExtra("id", 0);
        setTitle("Комментарии: " + getIntent().getStringExtra("title"));
        if (getActionBar() != null) getActionBar().setDisplayHomeAsUpEnabled(true);

        ListView list = new ListView(this);
        status = new TextView(this);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        status.setPadding(pad, pad, pad, pad);
        status.setTextColor(getResources().getColor(R.color.text_dim));
        list.addFooterView(status, null, false);
        adapter = new CommentAdapter();
        list.setAdapter(adapter);
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {
            }

            @Override
            public void onScroll(AbsListView view, int first, int visible, int total) {
                if (total > 0 && first + visible >= total - 5) loadMore();
            }
        });
        setContentView(list);
        loadMore();
    }

    @Override
    public boolean onNavigateUp() {
        finish();
        return true;
    }

    private void loadMore() {
        if (loading || !hasNext) return;
        loading = true;
        final int next = page + 1;
        status.setText("Загрузка…");
        Bg.run(new Bg.Job<LibApi.Paged<Comment>>() {
            @Override
            public LibApi.Paged<Comment> run() throws Exception {
                return App.get().api().comments(site, postType, postId, next);
            }
        }, new Bg.Done<LibApi.Paged<Comment>>() {
            @Override
            public void done(LibApi.Paged<Comment> result, Exception error) {
                if (isFinishing()) return;
                loading = false;
                if (error != null) {
                    hasNext = false;
                    status.setText(Bg.message(error));
                    return;
                }
                page = next;
                hasNext = result.hasNext;
                items.addAll(result.items);
                adapter.notifyDataSetChanged();
                status.setText(items.isEmpty() ? "Комментариев пока нет" : hasNext ? "" : "Это все комментарии");
            }
        });
    }

    private final class CommentAdapter extends BaseAdapter {
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
            return items.get(position).id;
        }

        @Override
        public boolean isEnabled(int position) {
            return false;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView != null ? convertView
                    : LayoutInflater.from(CommentsActivity.this).inflate(R.layout.item_comment, parent, false);
            Comment c = items.get(position);
            float d = getResources().getDisplayMetrics().density;
            // Ответы — с отступом, глубже трёх уровней не сдвигаем, иначе на 7" не останется места
            v.setPadding((int) ((12 + 20 * Math.min(c.level, 3)) * d), v.getPaddingTop(), v.getPaddingRight(),
                    v.getPaddingBottom());
            String when = c.createdAt > 0 ? DateUtils.getRelativeTimeSpanString(c.createdAt).toString() : "";
            String votes = c.up > 0 || c.down > 0 ? "  ▲" + c.up + " ▼" + c.down : "";
            ((TextView) v.findViewById(R.id.head)).setText((c.level > 0 ? "↳ " : "") + c.user + " · " + when + votes);
            ((TextView) v.findViewById(R.id.body)).setText(Html.fromHtml(c.html).toString().trim());
            return v;
        }
    }
}
