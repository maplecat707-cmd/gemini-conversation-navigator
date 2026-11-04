package io.github.geminianchor;

import android.content.Context;
import android.app.AlertDialog;
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
    interface SessionActions {
        java.util.List<SessionStore.Session> savedSessions();
        String activeSessionId();
        String activeSessionTitle();
        boolean remembersSessions();
        void enableMemory();
        void createSession(String name);
        void selectSession(String id);
        void renameSession(String name);
        void deleteSession();
        void startScanHere();
    }
    private final Context context;
    private final WindowManager manager;
    private final ConversationIndex index;
    private final Runnable scan, stop, diagnostics;
    private final SessionActions sessions;
    private AlertDialog dialog;
    private TextView sessionTitle;
    private final Consumer<ConversationIndex.Entry> seek;
    private View root;
    private TextView status, count;
    private LinearLayout items;
    private EditText search;
    private Button scanButton;
    private boolean expanded, busy;
    private String message = "点「收录前文」，收录右侧用户发言。";
    Sidebar(Context context, ConversationIndex index, Runnable scan, Runnable stop, Runnable diagnostics, Consumer<ConversationIndex.Entry> seek, SessionActions sessions) {
        this.context = context; this.index = index; this.scan = scan; this.stop = stop; this.diagnostics = diagnostics; this.seek = seek; this.sessions = sessions;
        manager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    }
    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
    private GradientDrawable background(String color, int radius) {
        GradientDrawable drawable = new GradientDrawable(); drawable.setColor(Color.parseColor(color)); drawable.setCornerRadius(dp(radius)); return drawable;
    }
    void show() { if (root == null) bubble(); }
    void hide() {
        if (dialog != null) { dialog.dismiss(); dialog = null; }
        if (root != null) {
            ((InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(root.getWindowToken(), 0);
            manager.removeView(root); root = null;
        }
        status = null; count = null; items = null; search = null; sessionTitle = null; expanded = false;
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
        TextView title = label("用户发言目录", 25); heading.addView(title, new LinearLayout.LayoutParams(0, dp(55), 1));
        Button close = button("×", this::collapse); close.setContentDescription("收起目录"); heading.addView(close, new LinearLayout.LayoutParams(dp(52), dp(48))); panel.addView(heading);
        sessionTitle = label(sessions.activeSessionTitle(), 14); panel.addView(sessionTitle);
        search = new EditText(context); search.setSingleLine(); search.setTextSize(14); search.setHint("搜索用户发言…");
        search.setContentDescription("搜索对话目录"); search.setTextColor(Color.parseColor("#29442F")); panel.addView(search);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { render(); }
            public void afterTextChanged(Editable text) {}
        });
        LinearLayout actions = new LinearLayout(context);
        scanButton = button(scanTitle(), () -> { if (busy) stop.run(); else scan.run(); });
        actions.addView(scanButton, new LinearLayout.LayoutParams(0, dp(50), 1));
        actions.addView(button("会话", this::sessionMenu), new LinearLayout.LayoutParams(0, dp(50), 1)); panel.addView(actions);
        count = label("", 12); panel.addView(count);
        ScrollView scroll = new ScrollView(context); items = new LinearLayout(context); items.setOrientation(LinearLayout.VERTICAL); scroll.addView(items);
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        status = label(message, 12); status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); panel.addView(status);
        panel.addView(button("复制识别诊断（不含对话）", diagnostics));
        if (!sessions.remembersSessions()) panel.addView(button("启用本机会话记忆", this::enableMemory));
        panel.addView(label(sessions.remembersSessions() ? "本机保存 · 离开或重启后可继续" : "尚未启用记忆 · 重启会丢失目录", 10));
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
        if (expanded) { if (scanButton != null) scanButton.setText(scanTitle()); }
        else if (root != null) { hide(); bubble(); }
    }
    void render() {
        if (items == null) return;
        scanButton.setText(scanTitle());
        if (sessionTitle != null) sessionTitle.setText(sessions.activeSessionTitle());
        items.removeAllViews();
        String query = search.getText().toString();
        var filtered = index.search(query);
        count.setText(filtered.size() + " / " + index.entries().size() + " 条用户发言 · 按对话顺序");
        if (filtered.isEmpty()) items.addView(label(index.entries().isEmpty() ? "点「收录前文」，把前面的用户发言放进目录。\n切换对话时在「会话」中选择或新建目录。" : "没有找到内容，试试更短的关键词。", 14));
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
    private void showDialog(AlertDialog next) {
        if (dialog != null) dialog.dismiss();
        dialog = next;
        next.getWindow().setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY);
        next.show();
    }
    private void enableMemory() {
        showDialog(new AlertDialog.Builder(context).setTitle("开启本机会话记忆")
                .setMessage("将用户发言、目录名称和续读位置保存在此手机的应用私有空间，离开 Gemini 或重启后仍保留。不上传、不备份。可在「会话」中删除单个目录，或在插件主页删除全部记忆。")
                .setNegativeButton("暂不", null)
                .setPositiveButton("开启", (d, which) -> { sessions.enableMemory(); open(); }).create());
    }
    private void editName(boolean create) {
        EditText name = new EditText(context); name.setSingleLine(); name.setHint("例如：旅行计划");
        if (!create) name.setText(sessions.activeSessionTitle());
        showDialog(new AlertDialog.Builder(context).setTitle(create ? "新建会话目录" : "重命名目录")
                .setMessage("先在 Gemini 打开对应对话，再选择此目录。新建不会删除其他目录。")
                .setView(name).setNegativeButton("取消", null)
                .setPositiveButton("保存", (d, which) -> {
                    if (create) sessions.createSession(name.getText().toString()); else sessions.renameSession(name.getText().toString());
                    open();
                }).create());
    }
    private void sessionMenu() {
        if (busy) stop.run();
        java.util.List<SessionStore.Session> saved = sessions.savedSessions();
        java.util.List<String> choices = new java.util.ArrayList<>();
        choices.add("＋ 新建会话目录"); choices.add("重命名当前目录");
        choices.add("从当前位置补收新发言"); choices.add("删除当前本机目录");
        for (SessionStore.Session s : saved) choices.add((s.id().equals(sessions.activeSessionId()) ? "✓ " : "")
                + s.title() + " · " + s.entries().size() + " 条");
        showDialog(new AlertDialog.Builder(context).setTitle("会话目录 · 请先在 Gemini 打开对应对话")
                .setItems(choices.toArray(new String[0]), (d, which) -> {
                    // Open the next dialog after Android dismisses this menu.
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                        if (root == null) return;
                        if (which == 0) editName(true);
                        else if (which == 1) editName(false);
                        else if (which == 2) sessions.startScanHere();
                        else if (which == 3) showDialog(new AlertDialog.Builder(context).setTitle("删除「" + sessions.activeSessionTitle() + "」？")
                                .setMessage("只删除此本机目录和续读位置，不删除 Gemini 对话。")
                                .setNegativeButton("取消", null)
                                .setPositiveButton("删除", (confirm, selected) -> { sessions.deleteSession(); open(); }).create());
                        else { sessions.selectSession(saved.get(which - 4).id()); open(); }
                    });
                }).setNegativeButton("关闭", null).create());
    }
    private String scanTitle() { return busy ? "停止" : index.entries().isEmpty() ? "收录前文" : "继续收录"; }
}
