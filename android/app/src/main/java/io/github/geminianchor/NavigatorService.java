package io.github.geminianchor;

import android.accessibilityservice.AccessibilityService;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Toast;
import io.github.geminianchor.core.ConversationIndex;
import java.util.List;

public final class NavigatorService extends AccessibilityService {
    static NavigatorService current;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ConversationIndex index = new ConversationIndex();
    private Sidebar sidebar;
    private static final long POLL_MS = 700;
    private static final long SCAN_IDLE_MS = 10000;
    private static final long SEEK_IDLE_MS = 6000;
    private static final int MAX_SCROLLS = 600;
    private boolean working, scanning, reversed, returning;
    private int steps, direction;
    private long lastProgressAt, nextScrollAt, operationStartedAt;
    private long scrollRevision, observedScrollRevision;
    private String scrollPosition = "";
    private String lastFingerprint = "", returnText;
    private ConversationIndex.Entry target;
    private final Runnable tick = this::step;
    private final Runnable foreground = this::refreshForeground;

    @Override protected void onServiceConnected() {
        current = this;
        sidebar = new Sidebar(this, index, this::scan, this::cancel, this::clear, this::copyDiagnostics, this::seek);
        refreshForeground();
    }
    private AccessibilityNodeInfo geminiRoot() {
        if (!getSharedPreferences("settings", MODE_PRIVATE).getBoolean("consent", false)) return null;
        List<AccessibilityWindowInfo> windows = getWindows();
        AccessibilityWindowInfo top = null;
        for (AccessibilityWindowInfo window : windows) {
            if (window.getType() == AccessibilityWindowInfo.TYPE_APPLICATION && (top == null || window.getLayer() > top.getLayer())) top = window;
        }
        AccessibilityNodeInfo root = top == null ? getRootInActiveWindow() : top.getRoot();
        boolean host = getSharedPreferences("settings", MODE_PRIVATE).getBoolean("googleHost", false);
        return ScreenReader.allowed(root, host) ? root : null;
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (sidebar == null) return;
        if (working && event.getEventType() == AccessibilityEvent.TYPE_VIEW_SCROLLED
                && ScreenReader.targetPackage(event.getPackageName())) {
            String position = event.getScrollY() + ":" + event.getMaxScrollY() + ":" + event.getFromIndex() + ":" + event.getToIndex();
            if (!position.equals(scrollPosition)) { scrollPosition = position; scrollRevision++; }
        }
        // All-app window events are needed to hide immediately on leaving Gemini.
        // Other apps' content events are ignored without inspecting their nodes.
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || ScreenReader.targetPackage(event.getPackageName())) {
            handler.removeCallbacks(foreground); handler.postDelayed(foreground, 180);
        }
    }
    private void refreshForeground() {
        if (sidebar == null) return;
        if (geminiRoot() == null) {
            stopWork(); sidebar.hide(); index.clear();
        } else sidebar.show();
    }
    private ScreenReader.Snapshot snapshot() {
        AccessibilityNodeInfo root = geminiRoot();
        return root == null ? null : ScreenReader.read(root);
    }
    private boolean readable(ScreenReader.Snapshot screen) { return screen != null && screen.scroller() != null; }
    void scan() {
        stopWork();
        ScreenReader.Snapshot screen = snapshot();
        if (!readable(screen) || screen.blocks().isEmpty()) {
            sidebar.message("没有读到可滚动的对话文字。请打开对话；当前 Gemini 版本也可能不支持读取。"); return;
        }
        // A stopped/limited scan must be resumable without discarding everything
        // already collected. Changing conversations still requires explicit clear.
        index.merge(screen.texts(), -1); sidebar.render();
        returnText = screen.blocks().get(0).text();
        working = true; scanning = true; returning = false; direction = -1; resetProgress();
        sidebar.busy(true); sidebar.collapse(); handler.postDelayed(tick, 250);
    }
    void seek(ConversationIndex.Entry entry) {
        stopWork(); target = entry; scanning = false; working = true; returning = false; reversed = false; resetProgress();
        ScreenReader.Snapshot screen = snapshot();
        int visibleIndex = -1;
        if (screen != null) {
            for (int i = 0; i < index.entries().size(); i++) {
                String text = index.entries().get(i).text();
                if (screen.texts().contains(text)) { visibleIndex = i; break; }
            }
        }
        direction = visibleIndex >= 0 && index.entries().indexOf(entry) > visibleIndex ? 1 : -1;
        sidebar.busy(true); sidebar.collapse(); handler.postDelayed(tick, 250);
    }
    private void step() {
        if (!working) return;
        long now = SystemClock.uptimeMillis();
        ScreenReader.Snapshot screen = snapshot();
        if (screen == null) { finish("已停止：离开了 Gemini。", false); refreshForeground(); return; }
        if (steps >= MAX_SCROLLS || now - operationStartedAt >= 10 * 60 * 1000L) {
            finish("本轮达到操作上限，已保留 " + index.entries().size() + " 条。点「继续收录」从当前位置接着读。", true); return;
        }
        if (!readable(screen)) {
            if (now - lastProgressAt >= SCAN_IDLE_MS) finish("页面暂时无法读取，已保留目录；内容加载后可继续收录。", true);
            else { sidebar.message("正在等待对话内容加载…"); handler.postDelayed(tick, POLL_MS); }
            return;
        }
        if (scanning) index.merge(screen.texts(), -1);
        else {
            for (ScreenReader.Block block : screen.blocks()) {
                if (block.text().equals(target.text())) {
                    block.node().performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId());
                    int ordinal = index.entries().indexOf(target) + 1;
                    finish(returning ? "已返回收录起点，保留 " + index.entries().size() + " 条。若仍缺前文可继续收录。"
                            : "已到达" + (ordinal > 0 ? "第 " + ordinal + " 条" : "收录起点") + "的匹配文字。", false);
                    return;
                }
            }
        }
        // Bounds detect progress even when one very long paragraph fills several screens.
        String fingerprint = screen.fingerprint() + screen.blocks().stream().map(b -> b.bounds().toShortString()).collect(java.util.stream.Collectors.toList());
        boolean changed = !fingerprint.equals(lastFingerprint) || observedScrollRevision != scrollRevision;
        if (changed) { lastProgressAt = now; nextScrollAt = now; }
        lastFingerprint = fingerprint; observedScrollRevision = scrollRevision;
        if (now - lastProgressAt >= (scanning ? SCAN_IDLE_MS : SEEK_IDLE_MS)) {
            if (scanning) {
                String origin = returnText;
                sidebar.message("连续 10 秒没有更早内容，已保留 " + index.entries().size() + " 条，正在返回起点…");
                target = new ConversationIndex.Entry(-1, origin); scanning = false; returning = true; direction = 1;
                reversed = true; resetProgress(); handler.postDelayed(tick, POLL_MS); return;
            }
            if (!reversed) { direction = -direction; reversed = true; resetProgress(); }
            else { finish("未找到匹配文字。对话可能已切换或内容发生变化，请重新收录。", true); return; }
        }
        if (now >= nextScrollAt) {
            if (ScreenReader.scroll(screen.scroller(), direction)) steps++;
            // Give asynchronous scrolling/history loads time to update the tree;
            // rejected actions are not evidence that the conversation has ended.
            nextScrollAt = now + 2100;
        }
        sidebar.message((scanning ? "正在收录前文 · " + index.entries().size() + " 条" : "正在定位") + " · 点击悬浮「停止」可中止");
        handler.postDelayed(tick, POLL_MS);
    }
    private void resetProgress() {
        steps = 0; lastFingerprint = ""; lastProgressAt = operationStartedAt = SystemClock.uptimeMillis();
        nextScrollAt = 0; observedScrollRevision = scrollRevision;
    }
    private void stopWork() { working = false; handler.removeCallbacks(tick); if (sidebar != null) sidebar.busy(false); }
    private void finish(String message, boolean open) {
        stopWork(); sidebar.message(message); sidebar.render();
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        if (open && geminiRoot() != null) sidebar.open();
    }
    void cancel() { finish("已停止，保留 " + index.entries().size() + " 条；可从当前位置继续收录。", true); }
    private void clear() { stopWork(); index.clear(); sidebar.message("已清空。请在当前对话中重新收录。"); sidebar.render(); }
    void copyDiagnostics() {
        AccessibilityNodeInfo root = geminiRoot();
        if (root == null) { sidebar.message("请先打开 Gemini 对话，再复制诊断。"); return; }
        String report = "plugin=" + BuildConfig.VERSION_NAME + "\nandroidApi=" + android.os.Build.VERSION.SDK_INT
                + "\nindexedEntries=" + index.entries().size() + "\n" + ScreenReader.diagnostics(root);
        ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Gemini Anchor 识别诊断", report));
        sidebar.message("已复制页面结构与数量，不含提问或回复文字。可粘贴给开发者排查漏收。");
    }
    void disableAndClear() { stopWork(); index.clear(); sidebar.hide(); current = null; disableSelf(); }
    @Override public void onInterrupt() { stopWork(); if (sidebar != null) sidebar.hide(); }
    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null); if (sidebar != null) sidebar.hide(); index.clear(); current = null; super.onDestroy();
    }
}
