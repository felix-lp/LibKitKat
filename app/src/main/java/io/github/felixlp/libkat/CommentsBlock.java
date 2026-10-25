package io.github.felixlp.libkat;

import android.content.Context;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import io.github.felixlp.libkat.core.Comment;
import io.github.felixlp.libkat.core.LibApi;
import io.github.felixlp.libkat.core.Site;

/** Комментарии к главе прямо в читалке, под последней страницей. Грузятся, только когда до них дочитали. */
final class CommentsBlock {
    private final Context context;
    private final LinearLayout root;
    private final LinearLayout list;
    private final TextView status;
    private final Button more;

    private Site site;
    private String postType;
    private int postId;
    private int page;
    private boolean started;
    private boolean loading;
    /** Ответы для прошлой главы, пришедшие после смены главы, отбрасываются. */
    private int generation;

    CommentsBlock(Context context) {
        this.context = context;
        float d = context.getResources().getDisplayMetrics().density;
        root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, (int) (12 * d), 0, (int) (24 * d));

        TextView title = new TextView(context);
        title.setText("Комментарии к главе");
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding((int) (12 * d), 0, 0, (int) (4 * d));
        root.addView(title);

        list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list);

        status = new TextView(context);
        status.setPadding((int) (12 * d), (int) (8 * d), (int) (12 * d), (int) (8 * d));
        status.setTextColor(context.getResources().getColor(R.color.text_dim));
        root.addView(status);

        more = new Button(context);
        more.setText("Ещё комментарии");
        more.setVisibility(View.GONE);
        more.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loadMore();
            }
        });
        root.addView(more);
    }

    View view() {
        return root;
    }

    void reset(Site site, String postType, int postId) {
        generation++;
        this.site = site;
        this.postType = postType;
        this.postId = postId;
        page = 0;
        started = false;
        loading = false;
        list.removeAllViews();
        status.setText("");
        more.setVisibility(View.GONE);
    }

    /** Вызывается, когда блок показался на экране; повторные вызовы ничего не делают. */
    void start() {
        if (started || site == null) return;
        started = true;
        loadMore();
    }

    private void loadMore() {
        if (loading) return;
        loading = true;
        final int gen = generation;
        final int next = page + 1;
        more.setVisibility(View.GONE);
        status.setText("Загрузка комментариев…");
        Bg.run(new Bg.Job<LibApi.Paged<Comment>>() {
            @Override
            public LibApi.Paged<Comment> run() throws Exception {
                return App.get().api().comments(site, postType, postId, next);
            }
        }, new Bg.Done<LibApi.Paged<Comment>>() {
            @Override
            public void done(LibApi.Paged<Comment> result, Exception error) {
                if (gen != generation) return;
                loading = false;
                if (error != null) {
                    status.setText(Bg.message(error));
                    started = false;
                    return;
                }
                page = next;
                LayoutInflater inflater = LayoutInflater.from(context);
                for (Comment c : result.items) {
                    View v = inflater.inflate(R.layout.item_comment, list, false);
                    CommentsActivity.bind(v, c);
                    list.addView(v);
                }
                status.setText(list.getChildCount() == 0 ? "Комментариев пока нет" : "");
                more.setVisibility(result.hasNext ? View.VISIBLE : View.GONE);
            }
        });
    }
}
