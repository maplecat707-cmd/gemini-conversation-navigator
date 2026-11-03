package io.github.geminianchor;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
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
    private boolean working, scanning, reversed;
    private int steps, direction, stalls;
    private String lastFingerprint = "", returnText;
    private ConversationIndex.Entry target;
    private final Runnable tick = this::step;
    private final Runnable foreground = this::refreshForeground;

    @Override protected void onServiceConnected() {
        current = this;
        sidebar = new Sidebar(this, index, this::scan, this::cancel, this::clear, this::seek);
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
        index.clear(); index.merge(screen.texts(), -1); sidebar.render();
        returnText = screen.blocks().get(0).text();
        working = true; scanning = true; direction = -1; steps = 0; stalls = 0; lastFingerprint = "";
        sidebar.busy(true); sidebar.collapse(); handler.postDelayed(tick, 250);
    }
    void seek(ConversationIndex.Entry entry) {
        stopWork(); target = entry; scanning = false; working = true; steps = 0; stalls = 0; reversed = false; lastFingerprint = "";
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
        ScreenReader.Snapshot screen = snapshot();
        if (!readable(screen)) { finish("已停止：离开了 Gemini 或页面不再支持滚动。", false); refreshForeground(); return; }
        if (++steps > 180) { finish("已达到本轮滚动上限，可再次收录或定位。", true); return; }
        if (scanning) index.merge(screen.texts(), -1);
        else {
            for (ScreenReader.Block block : screen.blocks()) {
                if (block.text().equals(target.text())) {
                    block.node().performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId());
                    int ordinal = index.entries().indexOf(target) + 1;
                    finish("已到达" + (ordinal > 0 ? "第 " + ordinal + " 段" : "收录起点") + "的匹配文字。", false);
                    return;
                }
            }
        }
        // Bounds detect progress even when one very long paragraph fills several screens.
        String fingerprint = screen.fingerprint() + screen.blocks().stream().map(b -> b.bounds().toShortString()).collect(java.util.stream.Collectors.toList());
        boolean same = fingerprint.equals(lastFingerprint); lastFingerprint = fingerprint;
        boolean moved = screen.scroller().performAction(direction < 0 ? AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD : AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
        stalls = (!moved || same) ? stalls + 1 : 0;
        if (stalls >= 4) {
            if (scanning) {
                String origin = returnText;
                stopWork(); sidebar.message("本轮收录 " + index.entries().size() + " 段，正在返回起点…");
                target = new ConversationIndex.Entry(-1, origin); scanning = false; working = true; direction = 1;
                steps = 0; stalls = 0; reversed = true; lastFingerprint = ""; sidebar.busy(true);
                handler.postDelayed(tick, 700); return;
            }
            if (!reversed) { direction = -direction; reversed = true; stalls = 0; lastFingerprint = ""; }
            else { finish("未找到匹配文字。对话可能已切换或内容发生变化，请重新收录。", true); return; }
        }
        sidebar.message((scanning ? "正在收录前文 · " + index.entries().size() + " 段" : "正在定位") + " · 点击悬浮「停止」可中止");
        handler.postDelayed(tick, 700);
    }
    private void stopWork() { working = false; handler.removeCallbacks(tick); if (sidebar != null) sidebar.busy(false); }
    private void finish(String message, boolean open) {
        stopWork(); sidebar.message(message); sidebar.render();
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        if (open && geminiRoot() != null) sidebar.open();
    }
    void cancel() { finish("已停止。已收录的 " + index.entries().size() + " 段保留在目录中。", true); }
    private void clear() { stopWork(); index.clear(); sidebar.message("已清空。请在当前对话中重新收录。"); sidebar.render(); }
    void disableAndClear() { stopWork(); index.clear(); sidebar.hide(); current = null; disableSelf(); }
    @Override public void onInterrupt() { stopWork(); if (sidebar != null) sidebar.hide(); }
    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null); if (sidebar != null) sidebar.hide(); index.clear(); current = null; super.onDestroy();
    }
}
