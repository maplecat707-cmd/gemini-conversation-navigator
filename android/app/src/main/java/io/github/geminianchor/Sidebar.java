package io.github.geminianchor;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import io.github.geminianchor.core.ConversationIndex;
import java.util.function.Consumer;

final class Sidebar {
    private final Context context;
    private final WindowManager manager;
    private final ConversationIndex index;
    private final Runnable scan, stop, clear;
    private final Consumer<ConversationIndex.Entry> seek;
    private View root;
    private TextView status, count;
    private LinearLayout items;
    private EditText search;
    private Button scanButton;
    private boolean expanded, busy;
    private String message = "打开目录后，点「收录前文」建立索引。";
    Sidebar(Context context, ConversationIndex index, Runnable scan, Runnable stop, Runnable clear, Consumer<ConversationIndex.Entry> seek) {
        this.context = context; this.index = index; this.scan = scan; this.stop = stop; this.clear = clear; this.seek = seek;
        manager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    }
    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
    private GradientDrawable background(String color, int radius) {
        GradientDrawable drawable = new GradientDrawable(); drawable.setColor(Color.parseColor(color)); drawable.setCornerRadius(dp(radius)); return drawable;
    }
    void show() { if (root == null) bubble(); }
    void hide() {
        if (root != null) {
            ((InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(root.getWindowToken(), 0);
            manager.removeView(root); root = null;
        }
        status = null; count = null; items = null; search = null; expanded = false;
    }
    void collapse() { hide(); bubble(); }
    private void attach(View view, int width, int height, boolean focusable) {
        root = view;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(width, height,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | (focusable ? 0 : WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE), PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.END | Gravity.CENTER_VERTICAL; params.x = dp(8);
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        manager.addView(view, params);
    }
    private Button button(String title, Runnable click) {
        Button view = new Button(context); view.setText(title); view.setTextSize(13); view.setAllCaps(false);
        view.setTextColor(Color.parseColor("#36543D")); view.setMinHeight(dp(48));
        view.setOnClickListener(v -> click.run()); return view;
    }
    private void bubble() {
        Button button = button(busy ? "停止" : "目录 " + index.entries().size(), () -> { if (busy) stop.run(); else open(); });
        button.setContentDescription(busy ? "停止自动滚动" : "展开 Gemini 对话目录");
        button.setBackground(background("#E6EEDE", 18)); button.setPadding(dp(14), dp(4), dp(14), dp(4));
        attach(button, WindowManager.LayoutParams.WRAP_CONTENT, dp(52), false);
    }
    void open() {
        hide(); expanded = true;
        LinearLayout panel = new LinearLayout(context); panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(18), dp(16), dp(12)); panel.setBackground(background("#F8FAF6", 22));
        TextView brand = label("GEMINI ANCHOR", 11); brand.setTextColor(Color.parseColor("#74856C")); panel.addView(brand);
        LinearLayout heading = new LinearLayout(context);
        TextView title = label("对话目录", 25); heading.addView(title, new LinearLayout.LayoutParams(0, dp(55), 1));
        Button close = button("×", this::collapse); close.setContentDescription("收起目录"); heading.addView(close, new LinearLayout.LayoutParams(dp(52), dp(48))); panel.addView(heading);
        search = new EditText(context); search.setSingleLine(); search.setTextSize(14); search.setHint("搜索已收录内容…");
        search.setContentDescription("搜索对话目录"); search.setTextColor(Color.parseColor("#29442F")); panel.addView(search);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { render(); }
            public void afterTextChanged(Editable text) {}
        });
        LinearLayout actions = new LinearLayout(context);
        scanButton = button(busy ? "停止" : "收录前文", () -> { if (busy) stop.run(); else scan.run(); });
        actions.addView(scanButton, new LinearLayout.LayoutParams(0, dp(50), 1));
        actions.addView(button("新对话 / 清空", clear), new LinearLayout.LayoutParams(0, dp(50), 1)); panel.addView(actions);
        count = label("", 12); panel.addView(count);
        ScrollView scroll = new ScrollView(context); items = new LinearLayout(context); items.setOrientation(LinearLayout.VERTICAL); scroll.addView(items);
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        status = label(message, 12); status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); panel.addView(status);
        panel.addView(label("仅在本机 · 目录只保留在内存", 10));
        panel.setFocusableInTouchMode(true); panel.requestFocus();
        panel.setOnKeyListener((v, key, event) -> { if (key == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) { collapse(); return true; } return false; });
        int screenWidth = context.getResources().getDisplayMetrics().widthPixels;
        int screenHeight = context.getResources().getDisplayMetrics().heightPixels;
        attach(panel, Math.min(dp(340), screenWidth - dp(24)), Math.max(dp(240), screenHeight - dp(100)), true);
        render();
    }
    private TextView label(String value, int size) {
        TextView text = new TextView(context); text.setText(value); text.setTextSize(size); text.setTextColor(Color.parseColor("#466146"));
        text.setPadding(dp(3), dp(6), dp(3), dp(6)); return text;
    }
    void message(String value) { message = value; if (status != null) status.setText(value); }
    void busy(boolean value) {
        busy = value;
        if (expanded) { if (scanButton != null) scanButton.setText(value ? "停止" : "收录前文"); }
        else if (root != null) { hide(); bubble(); }
    }
    void render() {
        if (items == null) return;
        items.removeAllViews();
        String query = search.getText().toString();
        var filtered = index.search(query);
        count.setText(filtered.size() + " / " + index.entries().size() + " 段 · 按收录顺序");
        if (filtered.isEmpty()) items.addView(label(index.entries().isEmpty() ? "点「收录前文」，把前面的内容放进目录。\n切换对话后请先清空。" : "没有找到内容，试试更短的关键词。", 14));
        for (ConversationIndex.Entry entry : filtered) {
            int ordinal = index.entries().indexOf(entry) + 1;
            Button row = button(String.format(java.util.Locale.ROOT, "%02d  %s", ordinal, entry.text()), () -> seek.accept(entry));
            row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL); row.setMaxLines(3);
            row.setEllipsize(android.text.TextUtils.TruncateAt.END); row.setPadding(dp(12), dp(10), dp(12), dp(10));
            row.setBackground(background("#EAF0E4", 10));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.bottomMargin = dp(7);
            items.addView(row, params);
        }
    }
}
