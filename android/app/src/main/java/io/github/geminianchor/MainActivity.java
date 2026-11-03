package io.github.geminianchor;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

public final class MainActivity extends Activity {
    private TextView state;
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView scroll = new ScrollView(this);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL); page.setPadding(dp(26), dp(36), dp(26), dp(32));
        scroll.addView(page); setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom()); return insets;
        });
        text(page, "GEMINI ANCHOR", 12, "#69806A");
        text(page, "长对话，有目录。", 30, "#29442F");
        text(page, "为 Gemini 添加随手可翻的侧边栏。\n搜索已收录内容，点击回到前面的位置。", 16, "#647663");
        state = text(page, "", 14, "#41684A");
        text(page, "01  开启目录服务", 19, "#29442F");
        text(page, "插件通过无障碍服务读取 Gemini 当前可见文字，在你点击「收录前文」或目录项时滚动对话。无需 Root，也不会修改 Gemini。", 14, "#647663");
        button(page, "开启无障碍服务", () -> new AlertDialog.Builder(this)
                .setTitle("允许读取 Gemini 屏幕内容？")
                .setMessage("开启后，插件会读取 Gemini 的可见文字，用于生成目录，并按你的指令滚动定位。内容仅保存在运行内存中；没有联网权限，不上传、不写入文件。离开 Gemini 会停止操作并隐藏侧栏。你可以随时在系统无障碍设置中关闭。")
                .setNegativeButton("暂不", null)
                .setPositiveButton("同意并去设置", (dialog, which) -> {
                    getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("consent", true).apply();
                    startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                }).show());
        text(page, "02  在 Gemini 中收录", 19, "#29442F");
        text(page, "打开对话 → 点击右侧「目录」→「收录前文」。插件会逐屏向上收录文字，完成后尝试返回起点。搜索关键词并点条目即可定位；自动滚动时点「停止」可随时中止。", 14, "#647663");
        button(page, "打开 Gemini", this::launchGemini);
        text(page, "03  切换对话时重新收录", 19, "#29442F");
        text(page, "Android 不提供稳定的会话 ID。切换对话后请点「新对话 / 清空」，再收录，避免旧条目混入。目录按可读取的文字段落排列，可能同时包含提问和回复；完全相同的文字只能定位到匹配位置。", 14, "#647663");
        Switch googleHost = new Switch(this);
        googleHost.setText("兼容由 Google App 承载的 Gemini");
        googleHost.setTextColor(Color.parseColor("#29442F")); googleHost.setTextSize(14);
        googleHost.setPadding(0, dp(18), 0, dp(18));
        googleHost.setChecked(getSharedPreferences("settings", MODE_PRIVATE).getBoolean("googleHost", false));
        googleHost.setOnCheckedChangeListener((button, checked) -> getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("googleHost", checked).apply());
        page.addView(googleHost);
        text(page, "仅当打开 Gemini 后没有目录时启用。启用后会检查 Google App 页面是否含 Gemini 标记；普通搜索页不收录。部分 Gemini 版本不暴露文字或滚动节点，此时无法定位。", 12, "#647663");
        button(page, "关闭服务并清空目录", () -> {
            NavigatorService service = NavigatorService.current;
            if (service != null) service.disableAndClear();
            getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("consent", false).apply();
            refreshState();
        });
        text(page, "独立开源项目，与 Google 无关联。\nAndroid 8.0+ · 对话内容仅保存在内存", 12, "#81907C");
    }
    private TextView text(LinearLayout parent, String content, int size, String color) {
        TextView view = new TextView(this); view.setText(content); view.setTextSize(size);
        view.setTextColor(Color.parseColor(color)); view.setPadding(0, dp(12), 0, dp(8));
        view.setLineSpacing(dp(4), 1); parent.addView(view); return view;
    }
    private void button(LinearLayout parent, String title, Runnable action) {
        Button button = new Button(this); button.setText(title); button.setAllCaps(false);
        button.setOnClickListener(v -> action.run()); parent.addView(button);
    }
    @Override protected void onResume() { super.onResume(); refreshState(); }
    private void refreshState() {
        state.setText(NavigatorService.current == null ? "○ 服务未开启" : "● 服务已连接 · 打开 Gemini 即可使用");
    }
    private void launchGemini() {
        Intent launch = getPackageManager().getLaunchIntentForPackage("com.google.android.apps.bard");
        if (launch == null) launch = getPackageManager().getLaunchIntentForPackage("com.google.android.googlequicksearchbox");
        if (launch != null) startActivity(launch);
        else new AlertDialog.Builder(this).setMessage("未找到 Gemini，请先安装官方 Android App。").setPositiveButton("知道了", null).show();
    }
}
