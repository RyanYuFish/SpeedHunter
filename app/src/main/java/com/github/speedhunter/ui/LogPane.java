package com.github.speedhunter.ui;

import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.IdRes;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.List;

/**
 * 原生日志面板：RecyclerView 逐行渲染，替换旧的「TextView 全量 setText +
 * ScrollingMovementMethod」。旧实现每来一行都整段重排（推送 106MB 包时刷几百行，
 * 主线程反复 measure/layout），而且 MovementMethod 没有惯性滚动（fling），手感生硬。
 * 这里一行一个 ViewHolder，天然复用 + 原生 fling；仅在用户本就停在底部时才自动跟随，
 * 向上翻阅时新日志不会把视口拽走。
 */
public final class LogPane {

    private final RecyclerView recycler;
    private final Adapter adapter = new Adapter();
    private final List<String> rows = new ArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final int maxLines;

    private LogPane(RecyclerView rv, int maxLines, String seed) {
        this.recycler = rv;
        this.maxLines = maxLines;
        rv.setLayoutManager(new LinearLayoutManager(rv.getContext()));
        rv.setAdapter(adapter);
        if (seed != null) {
            rows.add(seed);
        }
    }

    /** 把布局里旧的 tv_log（现已改为 RecyclerView）接成日志面板。 */
    public static LogPane attach(View root, @IdRes int id, int maxLines, String seed) {
        RecyclerView rv = root.findViewById(id);
        return new LogPane(rv, maxLines, seed);
    }

    public RecyclerView view() {
        return recycler;
    }

    public boolean isEmpty() {
        return rows.isEmpty();
    }

    public int count() {
        return rows.size();
    }

    /** 追加一行；任意线程可调（非主线程自动 post 到主线程）。 */
    public void append(final String line) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            appendInner(line);
        } else {
            main.post(() -> appendInner(line));
        }
    }

    private void appendInner(String line) {
        LinearLayoutManager lm = (LinearLayoutManager) recycler.getLayoutManager();
        int last = rows.size() - 1;
        // 用户是否停在底部（允许差 1 行的半可见）：决定这行来了以后要不要跟走
        boolean follow = lm != null && last >= 0
                && lm.findLastCompletelyVisibleItemPosition() >= last - 1;
        rows.add(line);
        int overflow = rows.size() - maxLines;
        if (overflow > 0) {
            rows.subList(0, overflow).clear();
            adapter.notifyItemRangeRemoved(0, overflow);
        }
        adapter.notifyItemInserted(rows.size() - 1);
        if (follow || rows.size() == 1) {
            recycler.scrollToPosition(rows.size() - 1);
        }
    }

    public void clear() {
        int n = rows.size();
        rows.clear();
        adapter.notifyItemRangeRemoved(0, n);
    }

    public void replaceAll(List<String> newRows) {
        rows.clear();
        rows.addAll(newRows);
        adapter.notifyDataSetChanged();
        if (!rows.isEmpty()) {
            recycler.scrollToPosition(rows.size() - 1);
        }
    }

    /** 全文（复制用）。行数被 cap 过之后与屏幕所见一致。 */
    public String text() {
        StringBuilder sb = new StringBuilder();
        for (String r : rows) {
            sb.append(r).append('\n');
        }
        return sb.toString();
    }

    private final class Adapter extends RecyclerView.Adapter<Row> {
        @Override
        public Row onCreateViewHolder(ViewGroup parent, int viewType) {
            Context c = parent.getContext();
            TextView tv = new TextView(c);
            tv.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            tv.setTypeface(android.graphics.Typeface.MONOSPACE);
            tv.setTextSize(11);
            tv.setTextColor(Color.parseColor("#1A1111"));
            // 长行不换行会丢信息：横向滚动保留整行
            tv.setSingleLine(false);
            tv.setHorizontalFadingEdgeEnabled(true);
            return new Row(tv);
        }

        @Override
        public void onBindViewHolder(Row holder, int position) {
            holder.text.setText(rows.get(position));
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }
    }

    private static final class Row extends RecyclerView.ViewHolder {
        final TextView text;

        Row(TextView tv) {
            super(tv);
            this.text = tv;
        }
    }
}