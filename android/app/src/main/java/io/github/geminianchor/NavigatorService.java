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
    private static final long POLL_MS = 160, IDLE_MS = 12000, MOVE_MS = 320;
    private static final int MAX_MOVES = 2000;
    private boolean working, scanning, reversed, positionDisabled, directAttempted, validating, probedStart;
    private int moves, direction, pendingRow = -1, eventFirst = -1, eventLast = -1, rowRequests, pageRequests;
    private long startedAt, lastProgressAt, nextMoveAt, pendingAt, revision, observedRevision, stableSince;
    private String lastFingerprint = "", scrollPosition = "", eventFingerprint = "", outcome = "尚未收录";
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
        AccessibilityWindowInfo top = null;
        for (AccessibilityWindowInfo window : getWindows()) {
            if (window.getType() == AccessibilityWindowInfo.TYPE_APPLICATION && (top == null || window.getLayer() > top.getLayer())) top = window;
        }
        AccessibilityNodeInfo root = top == null ? getRootInActiveWindow() : top.getRoot();
        return ScreenReader.allowed(root, getSharedPreferences("settings", MODE_PRIVATE).getBoolean("googleHost", false)) ? root : null;
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (sidebar == null) return;
        if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_SCROLLED && ScreenReader.targetPackage(event.getPackageName()) && geminiRoot() != null) {
            AccessibilityNodeInfo source = event.getSource();
            // Only the outer history's indices are valid anchors. Nested reply/code
            // scrollers have unrelated positions and must not overwrite them.
            if (source != null && ScreenReader.resource(source).equals("assistant_robin_chat_history_list")) {
                eventFirst = event.getFromIndex(); eventLast = event.getToIndex();
                eventFingerprint = ScreenReader.read(source).fingerprint();
            }
            String position = event.getScrollY() + ":" + event.getFromIndex() + ":" + event.getToIndex();
            boolean delta = android.os.Build.VERSION.SDK_INT >= 28 && event.getScrollDeltaY() != 0;
            if (!position.equals(scrollPosition) || delta) { scrollPosition = position; revision++; }
        }
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || ScreenReader.targetPackage(event.getPackageName())) {
            handler.removeCallbacks(foreground); handler.postDelayed(foreground, 180);
        }
    }
    private void refreshForeground() {
        if (sidebar == null) return;
        if (geminiRoot() == null) { stopWork(); sidebar.hide(); index.clear(); eventFirst = eventLast = -1; }
        else sidebar.show();
    }
    private ScreenReader.Snapshot snapshot() {
        AccessibilityNodeInfo root = geminiRoot();
        if (root == null) return null;
        ScreenReader.Snapshot screen = ScreenReader.read(root);
        // A single visible native row can be identified from its scroll event
        // when CollectionItemInfo is unavailable. Never infer multiple row IDs.
        if (screen.messages().size() == 1 && screen.firstRow() < 0 && eventFirst >= 0 && eventFirst == eventLast
                && screen.fingerprint().equals(eventFingerprint)
                && screen.scroller() != null && ScreenReader.resource(screen.scroller()).equals("assistant_robin_chat_history_list")) {
            ScreenReader.Block b = screen.messages().get(0);
            screen = new ScreenReader.Snapshot(screen.scroller(), List.of(new ScreenReader.Block(b.text(), b.bounds(), b.node(), eventFirst, b.role())), screen.viewport());
        }
        return screen;
    }
    void scan() {
        stopWork();
        ScreenReader.Snapshot screen = snapshot();
        if (screen == null || screen.scroller() == null) { sidebar.message("未读到聊天列表，请打开 Gemini 对话。"); return; }
        // A screen containing only an assistant reply is a valid starting point.
        scanning = true; working = true; direction = -1; resetOperation();
        sidebar.busy(true); sidebar.collapse(); handler.postDelayed(tick, POLL_MS);
    }
    void seek(ConversationIndex.Entry entry) {
        stopWork(); target = entry; scanning = false; working = true; direction = -1; resetOperation();
        ScreenReader.Snapshot screen = snapshot();
        if (screen != null) {
            if (entry.row() >= 0 && screen.firstRow() >= 0) direction = entry.row() > screen.lastRow() ? 1 : -1;
            else {
                for (int i = 0; i < index.entries().size(); i++) if (screen.texts().contains(index.entries().get(i).text())) {
                    direction = index.entries().indexOf(entry) > i ? 1 : -1; break;
                }
            }
        }
        sidebar.busy(true); sidebar.collapse(); handler.postDelayed(tick, POLL_MS);
    }
    private void resetOperation() {
        moves = rowRequests = pageRequests = 0; reversed = positionDisabled = directAttempted = validating = probedStart = false;
        pendingRow = -1; nextMoveAt = 0; lastFingerprint = ""; observedRevision = revision;
        startedAt = lastProgressAt = stableSince = SystemClock.uptimeMillis();
    }
    private void collect(ScreenReader.Snapshot screen) {
        List<ScreenReader.Block> prompts = screen.blocks();
        if (prompts.isEmpty()) return;
        if (prompts.stream().allMatch(b -> b.row() >= 0)) {
            // Position anchors supersede earlier text-only observations, so mixed
            // modes cannot show the same prompt twice.
            if (index.entries().stream().anyMatch(e -> e.row() < 0)) index.clear();
            for (ScreenReader.Block b : prompts) {
                if (index.entries().stream().anyMatch(e -> e.row() == b.row() && !e.text().equals(b.text()))) {
                    index.clear();
                    finish("列表位置或文字已变化，旧目录已清空。请回到对话末尾重新收录。", true);
                    return;
                }
                index.putAtRow(b.row(), b.text());
            }
        } else if (index.entries().stream().noneMatch(e -> e.row() >= 0)) index.merge(screen.texts(), direction);
    }
    private void step() {
        if (!working) return;
        long now = SystemClock.uptimeMillis();
        ScreenReader.Snapshot screen = snapshot();
        if (screen == null) { stopWork(); refreshForeground(); return; }
        if (moves >= MAX_MOVES || now - startedAt >= 15 * 60 * 1000L) {
            finish("达到本轮上限，已保留 " + index.entries().size() + " 条用户发言；可继续收录。", true); return;
        }
        if (screen.scroller() == null) {
            if (now - lastProgressAt >= IDLE_MS) finish("聊天列表暂时无法读取，已保留目录。", true);
            else schedule();
            return;
        }
        String fingerprint = screen.fingerprint();
        if (!fingerprint.equals(lastFingerprint)) { stableSince = now; lastProgressAt = now; }
        if (revision != observedRevision) lastProgressAt = now;
        lastFingerprint = fingerprint; observedRevision = revision;
        // Give a recycled row time to populate its children before indexing or
        // requesting the next row. An accepted action alone is not progress.
        if (now - stableSince < MOVE_MS) { schedule(); return; }
        if (screen.messages().stream().anyMatch(b -> b.role() == ScreenReader.Role.USER && b.text().isEmpty())) {
            if (now - lastProgressAt >= IDLE_MS) finish("用户发言文字尚未加载，已保留目录；加载后可继续收录。", true);
            else schedule();
            return;
        }
        if (scanning) { collect(screen); if (!working) return; }
        else if (checkTarget(screen, now)) return;

        if (pendingRow >= 0) {
            if (screen.hasRow(pendingRow)) {
                pendingRow = -1;
                if (!scanning && checkTarget(screen, now)) return;
            }
            else if (now - pendingAt < 1800) { schedule(); return; }
            else { pendingRow = -1; positionDisabled = true; }
        }
        if (scanning && screen.firstRow() == 0 && !screen.messages().isEmpty()) {
            // Probe above row zero once, then allow lazy-loaded older rows to
            // appear. Row zero proves the current list boundary, not server history.
            if (!probedStart) {
                probedStart = true; lastProgressAt = now;
                ScreenReader.scroll(screen.scroller(), -1); pageRequests++; moves++;
            } else if (now - lastProgressAt >= IDLE_MS) {
                finish("已到当前列表起点，收录 " + index.entries().size() + " 条用户发言；若还有前文，加载后可继续收录。", true); return;
            }
            schedule(); return;
        }
        probedStart = false;
        if (now - lastProgressAt >= IDLE_MS) {
            if (!scanning && !reversed) {
                direction = -direction; reversed = true; lastProgressAt = now;
            } else {
                finish(scanning ? "暂未发现更早位置，保留 " + index.entries().size() + " 条用户发言；可继续收录，不能确认已读完。"
                        : "未找到可验证的用户发言位置；请重新收录或复制诊断。", true); return;
            }
        }
        if (now >= nextMoveAt) {
            int row = scanning ? screen.firstRow() - 1 : target.row();
            if (row >= 0 && !positionDisabled && (scanning || !directAttempted)) {
                directAttempted = !scanning;
                invalidateEventRow();
                if (ScreenReader.scrollToRow(screen.scroller(), row)) {
                    pendingRow = row; pendingAt = now; rowRequests++; moves++; nextMoveAt = now + MOVE_MS;
                    schedule(); return;
                }
                positionDisabled = true;
            }
            if (!scanning && target.row() >= 0 && screen.firstRow() >= 0)
                direction = target.row() < screen.firstRow() ? -1 : target.row() > screen.lastRow() ? 1 : direction;
            invalidateEventRow();
            boolean accepted = ScreenReader.scroll(screen.scroller(), direction);
            pageRequests++; moves++; nextMoveAt = now + (accepted ? MOVE_MS : 800);
        }
        sidebar.message("已收录 " + index.entries().size() + " 条用户发言 · "
                + (scanning ? (rowRequests > 0 && !positionDisabled ? "按消息读取前文" : "向前查找用户发言") : "正在核对用户发言位置") + " · 可停止");
        schedule();
    }
    private boolean checkTarget(ScreenReader.Snapshot screen, long now) {
        ScreenReader.Block match = null;
        for (ScreenReader.Block b : screen.blocks()) {
            if (b.text().equals(target.text()) && (target.row() < 0 || b.row() == target.row())) { match = b; break; }
        }
        // An accepted scroll command is not proof of arrival. Confirm the next
        // snapshot still contains the right user prompt and that it is on screen.
        if (match != null && match.bounds().bottom > screen.viewport().top && match.bounds().top < screen.viewport().bottom) {
            if (!validating) {
                match.node().performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId());
                validating = true; nextMoveAt = now + 200; handler.postDelayed(tick, 200); return true;
            }
            finish("已定位到用户发言" + (target.row() >= 0 ? "（列表第 " + (target.row() + 1) + " 项）" : "的匹配文字（无行号，重复发言无法区分）"), false); return true;
        }
        if (validating) { validating = false; }
        if (target.row() >= 0 && screen.hasRow(target.row()) && pendingRow < 0 && directAttempted) {
            finish("该列表位置的内容已变化，没有确认到目标发言。请清空并重新收录。", true); return true;
        }
        return false;
    }
    private void invalidateEventRow() { eventFirst = eventLast = -1; eventFingerprint = ""; }
    private void schedule() { handler.postDelayed(tick, POLL_MS); }
    private void stopWork() { working = false; handler.removeCallbacks(tick); if (sidebar != null) sidebar.busy(false); }
    private void finish(String message, boolean open) {
        outcome = message; stopWork(); sidebar.message(message); sidebar.render();
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        if (open && geminiRoot() != null) sidebar.open();
    }
    void cancel() { finish("已停止，保留 " + index.entries().size() + " 条用户发言，可继续收录。", true); }
    private void clear() { stopWork(); index.clear(); eventFirst = eventLast = -1; sidebar.message("已清空，请重新收录当前对话的用户发言。"); sidebar.render(); }
    void copyDiagnostics() {
        AccessibilityNodeInfo root = geminiRoot();
        if (root == null) { sidebar.message("请先打开 Gemini 对话。"); return; }
        String report = "plugin=" + BuildConfig.VERSION_NAME + "\nandroidApi=" + android.os.Build.VERSION.SDK_INT
                + "\nuserEntries=" + index.entries().size() + " rowRequests=" + rowRequests + " pageRequests=" + pageRequests
                + " eventFirst=" + eventFirst + " eventLast=" + eventLast + "\nstatus=" + outcome + "\n" + ScreenReader.diagnostics(root);
        ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Gemini Anchor 识别诊断", report));
        sidebar.message("已复制行号、左右位置与滚动情况，不含对话文字。请粘贴反馈。");
    }
    void disableAndClear() { stopWork(); index.clear(); sidebar.hide(); current = null; disableSelf(); }
    @Override public void onInterrupt() { stopWork(); if (sidebar != null) sidebar.hide(); }
    @Override public void onDestroy() { handler.removeCallbacksAndMessages(null); if (sidebar != null) sidebar.hide(); index.clear(); current = null; super.onDestroy(); }
}
