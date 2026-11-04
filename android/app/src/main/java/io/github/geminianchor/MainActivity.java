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
        text(page, "为 Gemini 添加随手可翻的侧边栏。\n搜索用户发言，点击回到前面的位置。", 16, "#647663");
        state = text(page, "", 14, "#41684A");
        text(page, "01  开启目录服务", 19, "#29442F");
        text(page, "插件通过无障碍服务读取 Gemini 当前可见文字，在你点击「收录前文」或目录项时滚动对话。无需 Root，也不会修改 Gemini。", 14, "#647663");
        button(page, "开启无障碍服务", () -> new AlertDialog.Builder(this)
                .setTitle("允许读取 Gemini 屏幕内容？")
                .setMessage("开启后，插件会读取 Gemini 的可见文字，用于生成目录，并按你的指令滚动定位。开启本机会话记忆后，用户发言和续读位置会保存在手机的应用私有空间；没有联网权限，不上传、不备份。未开启记忆时仅保存在运行内存。离开 Gemini 会停止操作并隐藏侧栏。你可以随时在系统无障碍设置中关闭。")
                .setNegativeButton("暂不", null)
                .setPositiveButton("同意并去设置", (dialog, which) -> {
                    getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("consent", true).apply();
                    startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                }).show());
        text(page, "02  在 Gemini 中收录", 19, "#29442F");
        text(page, "打开对话末尾 → 点击右侧「目录」→「收录前文」。只收录你的发言，跳过长回复。收录后停在前文，展开目录即可搜索定位；没有读完可点「继续收录」，先回到已记住的位置再往上翻。自动滚动时点「停止」可随时中止。", 14, "#647663");
        button(page, "打开 Gemini", this::launchGemini);
        text(page, "03  多个会话分别记忆", 19, "#29442F");
        text(page, "在目录的「会话」菜单中，新建、切换、重命名或删除目录。先在 Gemini 打开对应对话，再选已保存的目录。Gemini 卡住时可停止并重新打开；开启记忆后，重启插件也能恢复目录，点「继续收录」回到上次最早收录的发言后继续。要加入后来新增的提问，停在对话末尾，在「会话」中选「从当前位置补收新发言」。", 14, "#647663");
        text(page, "记忆只保存在本机，包含用户发言、目录名称与位置；不上传、不备份。Gemini 没有可靠的会话 ID，需要手动选对应目录。记忆不能解除 Gemini 自身卡顿，也不能恢复 App 尚未加载的内容。", 14, "#647663");
        button(page, "开启本机会话记忆", () -> {
            getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("rememberSessions", true).apply();
            if (NavigatorService.current != null) NavigatorService.current.enableMemory();
            refreshState();
        });
        button(page, "删除所有本机会话记忆", () -> new AlertDialog.Builder(this)
                .setTitle("删除所有本机目录？").setMessage("删除已保存的用户发言和续读位置。Gemini 中的对话不受影响，并会关闭自动记忆。")
                .setNegativeButton("取消", null).setPositiveButton("删除", (d, which) -> {
                    deleteMemory(false); refreshState();
                }).show());
        Switch googleHost = new Switch(this);
        googleHost.setText("兼容由 Google App 承载的 Gemini");
        googleHost.setTextColor(Color.parseColor("#29442F")); googleHost.setTextSize(14);
        googleHost.setPadding(0, dp(18), 0, dp(18));
        googleHost.setChecked(getSharedPreferences("settings", MODE_PRIVATE).getBoolean("googleHost", false));
        googleHost.setOnCheckedChangeListener((button, checked) -> getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("googleHost", checked).apply());
        page.addView(googleHost);
        text(page, "仅当打开 Gemini 后没有目录时启用。启用后会检查 Google App 页面是否含 Gemini 标记；普通搜索页不收录。部分 Gemini 版本不暴露文字或滚动节点，此时无法定位。", 12, "#647663");
        button(page, "关闭服务并删除全部记忆", () -> new AlertDialog.Builder(this)
                .setTitle("关闭服务并删除全部记忆？").setMessage("将删除此手机上保存的所有目录与续读位置。Gemini 中的对话不受影响。")
                .setNegativeButton("取消", null).setPositiveButton("关闭并删除", (d, which) -> {
                    deleteMemory(true); refreshState();
                }).show());
        text(page, "独立开源项目，与 Google 无关联。\nAndroid 8.0+ · 本机会话记忆可随时删除", 12, "#81907C");
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
        state.setText((NavigatorService.current == null ? "○ 服务未开启 · " : "● 服务已连接 · ") + BuildConfig.VERSION_NAME
                + (getSharedPreferences("settings", MODE_PRIVATE).getBoolean("rememberSessions", false) ? "\n本机会话记忆已开启" : "\n本机会话记忆尚未开启"));
    }
    private void deleteMemory(boolean disable) {
        NavigatorService service = NavigatorService.current;
        if (service != null) { if (disable) service.disableAndClear(); else service.deleteAllMemory(); }
        else new SessionStore(this, false).deleteAll();
        getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("rememberSessions", false).apply();
        if (disable) getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("consent", false).apply();
    }
    private void launchGemini() {
        Intent launch = getPackageManager().getLaunchIntentForPackage("com.google.android.apps.bard");
        if (launch == null) launch = getPackageManager().getLaunchIntentForPackage("com.google.android.googlequicksearchbox");
        if (launch != null) startActivity(launch);
        else new AlertDialog.Builder(this).setMessage("未找到 Gemini，请先安装官方 Android App。").setPositiveButton("知道了", null).show();
    }
}
