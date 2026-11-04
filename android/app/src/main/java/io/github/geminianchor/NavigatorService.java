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

public final class NavigatorService extends AccessibilityService implements Sidebar.SessionActions {
    static NavigatorService current;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ConversationIndex index = new ConversationIndex();
    private Sidebar sidebar;
    private SessionStore sessions;
    private boolean memoryEnabled, memoryDirty, saveQueued, resumeScan, supplementing;
    private long mergeBeforeId = -1;
    private java.util.concurrent.Future<Boolean> pendingSave;
    private final Runnable saveMemory = () -> { saveQueued = false; persistMemory(); };
    private final Runnable checkSave = this::checkSaved;
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
        memoryEnabled = getSharedPreferences("settings", MODE_PRIVATE).getBoolean("rememberSessions", false);
        sessions = new SessionStore(this, memoryEnabled);
        index.restore(sessions.active().entries());
        sidebar = new Sidebar(this, index, this::scan, this::cancel, this::copyDiagnostics, this::seek, this);
        sidebar.message(sessions.loadFailed() ? "本机会话记忆无法读取，原文件已保留；请勿继续覆盖。"
                : index.entries().isEmpty() ? "点「收录前文」开始；可在「会话」中保存多个目录。"
                : "已恢复上次目录。先打开对应的 Gemini 对话，点「继续收录」接着往上翻。");
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
        if (geminiRoot() == null) { stopWork(); sidebar.hide(); eventFirst = eventLast = -1; }
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
        if (!index.entries().isEmpty()) { beginSeek(index.entries().get(0), true); return; }
        startScanHere();
    }
    @Override public void startScanHere() {
        stopWork(); resumeScan = false;
        ScreenReader.Snapshot screen = snapshot();
        if (screen == null || screen.scroller() == null) { sidebar.message("未读到聊天列表，请打开 Gemini 对话。"); return; }
        // A screen containing only an assistant reply is a valid starting point.
        scanning = true; working = true; direction = -1; resetOperation();
        supplementing = !index.entries().isEmpty();
        sidebar.busy(true); sidebar.collapse(); handler.postDelayed(tick, POLL_MS);
    }
    void seek(ConversationIndex.Entry entry) { beginSeek(entry, false); }
    private void beginSeek(ConversationIndex.Entry entry, boolean resume) {
        stopWork(); resumeScan = resume; target = entry; scanning = false; working = true; direction = -1; resetOperation();
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
        mergeBeforeId = -1; supplementing = false;
        moves = rowRequests = pageRequests = 0; reversed = positionDisabled = directAttempted = validating = probedStart = false;
        pendingRow = -1; nextMoveAt = 0; lastFingerprint = ""; observedRevision = revision;
        startedAt = lastProgressAt = stableSince = SystemClock.uptimeMillis();
    }
    private void collect(ScreenReader.Snapshot screen) {
        List<ScreenReader.Block> prompts = screen.blocks();
        if (prompts.isEmpty()) return;
        List<ConversationIndex.Entry> before = List.copyOf(index.entries());
        if (prompts.stream().allMatch(b -> b.row() >= 0) && index.entries().stream().noneMatch(e -> e.row() < 0)) {
            if (prompts.stream().anyMatch(b -> index.entries().stream().anyMatch(e -> e.row() == b.row() && !e.text().equals(b.text())))) {
                finish("该位置的文字与已存目录不同，已保留原记忆并停止。请检查所选会话；若编辑了对话，可新建目录重收。", true);
                return;
            }
            for (ScreenReader.Block b : prompts) index.putAtRow(b.row(), b.text());
        } else {
            // Row metadata can disappear as RecyclerView loads. Keep the saved
            // directory and fall back to text matching, never discard its pages.
            if (index.entries().stream().anyMatch(e -> e.row() >= 0))
                index.restore(index.entries().stream().map(e -> new ConversationIndex.Entry(e.id(), e.text()))
                        .collect(java.util.stream.Collectors.toList()));
            int insertion = supplementing ? index.entries().size() : 0;
            for (int i = 0; i < index.entries().size(); i++) if (index.entries().get(i).id() == mergeBeforeId) { insertion = i; break; }
            index.merge(screen.texts(), direction, insertion);
        }
        for (ConversationIndex.Entry entry : index.entries()) {
            if (entry.text().equals(prompts.get(0).text())) { mergeBeforeId = entry.id(); break; }
        }
        if (!before.equals(index.entries())) {
            sessions.update(index.entries()); queueMemorySave();
        }
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
                finish(scanning ? "页面暂未前进，保留 " + index.entries().size() + " 条用户发言；Gemini 恢复后可继续收录，不能确认已读完。"
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
            pageRequests++; moves++;
            long stalledFor = now - lastProgressAt;
            long interval = stalledFor >= 5000 ? 1800 : stalledFor >= 2000 ? 900 : MOVE_MS;
            nextMoveAt = now + Math.max(interval, accepted ? MOVE_MS : 800);
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
            if (resumeScan) {
                resumeScan = false; scanning = true; direction = -1; resetOperation();
                sidebar.message("已回到上次最早收录的发言，继续向上读取。"); schedule(); return true;
            }
            finish("已定位到用户发言" + (target.row() >= 0 ? "（列表第 " + (target.row() + 1) + " 项）" : "的匹配文字（无行号，重复发言无法区分）"), false); return true;
        }
        if (validating) { validating = false; }
        if (target.row() >= 0 && screen.hasRow(target.row()) && pendingRow < 0 && directAttempted) {
            finish("该列表位置的内容已变化，没有确认到目标发言。已保留记忆，请检查所选会话或新建目录重收。", true); return true;
        }
        return false;
    }
    private void invalidateEventRow() { eventFirst = eventLast = -1; eventFingerprint = ""; }
    private void schedule() { handler.postDelayed(tick, POLL_MS); }
    private void stopWork() {
        working = false; handler.removeCallbacks(tick); persistMemory();
        if (sidebar != null) sidebar.busy(false);
    }
    private void finish(String message, boolean open) {
        outcome = message; stopWork(); sidebar.message(message); sidebar.render();
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        if (open && geminiRoot() != null) sidebar.open();
    }
    void cancel() { finish("已停止，保留 " + index.entries().size() + " 条用户发言，可继续收录。", true); }
    void copyDiagnostics() {
        AccessibilityNodeInfo root = geminiRoot();
        if (root == null) { sidebar.message("请先打开 Gemini 对话。"); return; }
        String report = "plugin=" + BuildConfig.VERSION_NAME + "\nandroidApi=" + android.os.Build.VERSION.SDK_INT
                + "\nuserEntries=" + index.entries().size() + " rowRequests=" + rowRequests + " pageRequests=" + pageRequests
                + " eventFirst=" + eventFirst + " eventLast=" + eventLast
                + " memoryEnabled=" + memoryEnabled + " savedSessions=" + sessions.sessions().size() + " resuming=" + resumeScan + "\nstatus=" + outcome + "\n" + ScreenReader.diagnostics(root);
        ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Gemini Anchor 识别诊断", report));
        sidebar.message("已复制行号、左右位置与滚动情况，不含对话文字。请粘贴反馈。");
    }
    @Override public List<SessionStore.Session> savedSessions() { return sessions.sessions(); }
    @Override public String activeSessionId() { return sessions.active().id(); }
    @Override public String activeSessionTitle() { return sessions.active().title(); }
    @Override public boolean remembersSessions() { return memoryEnabled; }
    @Override public void enableMemory() {
        if (sessions.loadFailed()) { sidebar.message("无法读取原记忆文件，请先在插件主页删除损坏的记忆后重试。"); return; }
        memoryEnabled = true;
        getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("rememberSessions", true).apply();
        sessions.update(index.entries()); queueMemorySave(); persistMemory();
        sidebar.message("已开启本机会话记忆，目录和续读进度会自动保存。"); sidebar.render();
    }
    @Override public void createSession(String name) {
        stopWork(); sessions.create(name); index.clear(); invalidateEventRow();
        queueMemorySave(); persistMemory(); sidebar.message("已新建目录，请先打开对应的 Gemini 对话，再点「收录前文」。");
    }
    @Override public void selectSession(String id) {
        stopWork();
        if (!sessions.select(id)) return;
        index.restore(sessions.active().entries()); invalidateEventRow(); queueMemorySave(); persistMemory();
        sidebar.message("已切换目录。请先在 Gemini 打开对应对话，再定位或继续收录。");
    }
    @Override public void renameSession(String name) { sessions.rename(name); queueMemorySave(); persistMemory(); }
    @Override public void deleteSession() {
        stopWork(); sessions.deleteActive(); index.restore(sessions.active().entries()); invalidateEventRow();
        queueMemorySave(); persistMemory(); sidebar.message("已删除所选本机目录；Gemini 中的对话不受影响。");
    }
    private void queueMemorySave() {
        memoryDirty = true;
        if (memoryEnabled && !saveQueued) {
            saveQueued = true; handler.postDelayed(saveMemory, 750);
        }
    }
    private void persistMemory() {
        if (!memoryEnabled || !memoryDirty || sessions == null) return;
        handler.removeCallbacks(saveMemory); saveQueued = false; memoryDirty = false;
        pendingSave = sessions.save(); handler.removeCallbacks(checkSave); handler.postDelayed(checkSave, 200);
    }
    private void checkSaved() {
        if (pendingSave == null) return;
        if (!pendingSave.isDone()) { handler.postDelayed(checkSave, 200); return; }
        try {
            if (!pendingSave.get()) { memoryDirty = true; sidebar.message("本次记忆未能保存，当前目录仍在内存中。请检查手机存储空间。"); }
        } catch (Exception failure) { memoryDirty = true; sidebar.message("本次记忆保存失败，当前目录仍在内存中。"); }
    }
    void deleteAllMemory() {
        stopWork(); memoryEnabled = false; memoryDirty = false; saveQueued = false;
        handler.removeCallbacks(saveMemory); handler.removeCallbacks(checkSave);
        getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("rememberSessions", false).apply();
        pendingSave = sessions.deleteAll(); index.clear(); invalidateEventRow(); sidebar.hide();
    }
    void disableAndClear() { deleteAllMemory(); current = null; disableSelf(); }
    @Override public void onInterrupt() { stopWork(); if (sidebar != null) sidebar.hide(); }
    @Override public void onDestroy() {
        persistMemory(); handler.removeCallbacksAndMessages(null);
        if (sidebar != null) sidebar.hide(); index.clear(); current = null; super.onDestroy();
    }
}
