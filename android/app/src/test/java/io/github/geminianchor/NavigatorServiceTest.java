package io.github.geminianchor;

import android.content.Context;
import android.graphics.Rect;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import io.github.geminianchor.core.ConversationIndex;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import java.time.Duration;
import java.util.List;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Drives the actual service with paginated accessibility windows, no Gemini account. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 35})
public class NavigatorServiceTest {
    private NavigatorService service;
    private AccessibilityNodeInfo root, text, scroller;
    private int page, scrolls;
    @Before public void setup() {
        service = Robolectric.buildService(NavigatorService.class).create().get();
        service.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean("consent", true).commit();
        root = node(null, 0, 1920); root.setPackageName("com.google.android.apps.bard");
        scroller = node(null, 180, 1500); scroller.setScrollable(true);
        page = 9; text = node("段落 9", 220, 250);
        text.setViewIdResourceName("test:id/user_query");
        shadowOf(root).addChild(scroller); shadowOf(scroller).addChild(text);
        shadowOf(scroller).setOnPerformActionListener((action, args) -> {
            int next = page;
            if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) next--;
            if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) next++;
            if (next < 0 || next > 9 || next == page) return false;
            page = next; scrolls++; text.setText("段落 " + page); return true;
        });
        AccessibilityWindowInfo window = AccessibilityWindowInfo.obtain();
        shadowOf(window).setType(AccessibilityWindowInfo.TYPE_APPLICATION);
        shadowOf(window).setRoot(root); shadowOf(window).setLayer(1);
        shadowOf(service).setWindows(List.of(window));
        service.onServiceConnected();
    }
    @After public void cleanup() { service.onDestroy(); }
    private AccessibilityNodeInfo node(String value, int top, int height) {
        AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain(); node.setText(value);
        node.setVisibleToUser(true); node.setBoundsInScreen(new Rect(0, top, 1080, top + height)); return node;
    }
    private ConversationIndex index() { return ReflectionHelpers.getField(service, "index"); }
    private void advance(int seconds) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds)); }
    @Test public void scansEarlierUserPagesInOrderStaysAtEarliestAndNavigates() {
        service.scan(); advance(30);
        assertEquals(10, index().entries().size());
        assertEquals("段落 0", index().entries().get(0).text());
        assertEquals("段落 9", index().entries().get(9).text());
        assertEquals("scan stays at earliest position to avoid a second slow traversal", 0, page);
        service.seek(index().entries().get(2)); advance(15);
        assertEquals(2, page);
        assertTrue(shadowOf(text).getPerformedActions().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId()));
        assertFalse(ReflectionHelpers.<Boolean>getField(service, "working"));
    }
    @Test public void cancelStopsQueuedScrollingAndKeepsAlreadyCollectedText() {
        service.scan(); advance(2); service.cancel();
        int position = page, count = index().entries().size(), actions = scrolls;
        advance(20);
        assertEquals(position, page); assertEquals(actions, scrolls); assertEquals(count, index().entries().size());
        assertTrue(count > 0 && count < 10);
    }
    @Test public void leavingGeminiStopsScrollingAndKeepsDirectoryForResume() {
        service.scan(); advance(2);
        root.setPackageName("org.example.other");
        AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
        event.setPackageName("org.example.other"); service.onAccessibilityEvent(event); advance(1);
        int actions = scrolls; advance(10);
        assertFalse(index().entries().isEmpty()); assertEquals(actions, scrolls);
        assertFalse(ReflectionHelpers.<Boolean>getField(service, "working"));
    }
    @Test public void missingTargetStopsAtBoundsInsteadOfScrollingForever() {
        service.seek(new ConversationIndex.Entry(-1, "没有这个内容")); advance(40);
        assertFalse(ReflectionHelpers.<Boolean>getField(service, "working"));
        int actions = scrolls; advance(20); assertEquals(actions, scrolls);
    }
    @Test public void supportsDirectionalScrollActionsWhenForwardBackwardAreRejected() {
        scroller.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP);
        scroller.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN);
        shadowOf(scroller).setOnPerformActionListener((action, args) -> {
            int next = page;
            if (action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.getId()) next--;
            if (action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.getId()) next++;
            if (next < 0 || next > 9 || next == page) return false;
            page = next; scrolls++; text.setText("段落 " + page); return true;
        });
        service.scan(); advance(45);
        assertEquals(10, index().entries().size());
        assertEquals(0, page);
    }
    @Test public void historyTakingSeveralSecondsToLoadIsNotTreatedAsTheBeginning() {
        final boolean[] pending = {false};
        android.os.Handler loader = new android.os.Handler(Looper.getMainLooper());
        shadowOf(scroller).setOnPerformActionListener((action, args) -> {
            if (pending[0]) return false;
            int delta = action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD ? -1
                    : action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ? 1 : 0;
            int next = page + delta;
            if (next < 0 || next > 9 || next == page) return false;
            pending[0] = true;
            loader.postDelayed(() -> { page = next; scrolls++; text.setText("段落 " + page); pending[0] = false; }, 4200);
            return true;
        });
        service.scan(); advance(120);
        assertEquals(10, index().entries().size());
        assertEquals(0, page);
    }
    @Test public void continuingAScanPreservesAlreadyCollectedLaterMessages() {
        service.scan(); advance(2); service.cancel();
        long lastId = index().entries().get(index().entries().size() - 1).id();
        service.scan(); advance(35);
        assertEquals(10, index().entries().size());
        assertEquals("段落 9", index().entries().get(9).text());
        assertEquals(lastId, index().entries().get(9).id());
        assertEquals(0, page);
    }
    @Test public void scrollEventsKeepScanningThroughALongUnchangedMessage() {
        page = 60; text.setText("占据很多屏的同一条回复"); text.setViewIdResourceName("test:id/model_response");
        shadowOf(scroller).setOnPerformActionListener((action, args) -> {
            int next = page + (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD ? -1 : 1);
            if (next < 0 || next > 60) return false;
            page = next; text.setText(page == 0 ? "最早的提问" : "占据很多屏的同一条回复");
            text.setViewIdResourceName(page == 0 ? "test:id/user_query" : "test:id/model_response");
            AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_SCROLLED);
            event.setPackageName("com.google.android.apps.bard"); event.setScrollY(0); event.setScrollDeltaY(-800); event.setMaxScrollY(0);
            service.onAccessibilityEvent(event); return true;
        });
        service.scan(); advance(60);
        assertEquals("最早的提问", index().entries().get(0).text());
        assertFalse(ReflectionHelpers.<Boolean>getField(service, "working"));
    }
    @Test public void diagnosticClipboardContainsStructureButNoConversation() {
        text.setText("私人提问不应出现在诊断中"); text.setContentDescription("私人描述也不能出现");
        service.copyDiagnostics();
        android.content.ClipboardManager clipboard = (android.content.ClipboardManager) service.getSystemService(Context.CLIPBOARD_SERVICE);
        String report = clipboard.getPrimaryClip().getItemAt(0).getText().toString();
        assertTrue(report.contains("userPrompts=")); assertTrue(report.contains("textChars="));
        assertFalse(report.contains("私人提问")); assertFalse(report.contains("私人描述"));
    }

    private void nativeRow(int row) {
        page = row;
        text.setCollectionItemInfo(AccessibilityNodeInfo.CollectionItemInfo.obtain(row, 1, 0, 1, false));
        text.setViewIdResourceName("test:id/assistant_robin_content_message_container");
        boolean user = row % 2 == 0;
        text.setBoundsInScreen(user ? new Rect(300, 220, 1020, 470) : new Rect(60, 180, 1020, 1680));
        text.setText(user ? (row == 10 || row == 100 ? "继续" : "提问 " + row / 2) : "长回复".repeat(1500));
    }
    private void nativeHistory(int lastRow, boolean effective) {
        scroller.setViewIdResourceName("test:id/assistant_robin_chat_history_list");
        scroller.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_TO_POSITION);
        nativeRow(lastRow);
        shadowOf(scroller).setOnPerformActionListener((action, args) -> {
            if (action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_TO_POSITION.getId()) {
                if (effective) nativeRow(args.getInt(AccessibilityNodeInfo.ACTION_ARGUMENT_ROW_INT));
                return true;
            }
            int next = page + (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD ? -1 : 1);
            if (next < 0 || next > lastRow) return false;
            scrolls++; nativeRow(next); return true;
        });
    }
    @Test public void sixtyTurnsStartingOnLongReplyUseRowsAndKeepRepeatedPromptsDistinct() {
        nativeHistory(119, true);
        service.scan(); advance(85);
        assertFalse(ReflectionHelpers.<Boolean>getField(service, "working"));
        assertEquals(60, index().entries().size()); assertEquals(0, page);
        assertEquals("提问 0", index().entries().get(0).text());
        assertEquals("提问 59", index().entries().get(59).text());
        assertEquals(119, (int) ReflectionHelpers.<Integer>getField(service, "rowRequests"));
        assertEquals("only a boundary probe; no paging through the long replies", 1,
                (int) ReflectionHelpers.<Integer>getField(service, "pageRequests"));
        List<ConversationIndex.Entry> repeated = index().search("继续");
        assertEquals(2, repeated.size()); assertNotEquals(repeated.get(0).id(), repeated.get(1).id());
        service.seek(repeated.get(1)); advance(3);
        assertEquals(100, page); assertTrue(ReflectionHelpers.<String>getField(service, "outcome").startsWith("已定位"));
        assertEquals(1, (int) ReflectionHelpers.<Integer>getField(service, "rowRequests"));
    }
    @Test public void acceptedButIneffectivePositionActionFallsBackToPaging() {
        nativeHistory(9, false);
        service.scan(); advance(25);
        assertEquals(5, index().entries().size()); assertEquals(0, page);
        assertTrue(scrolls > 0); assertFalse(ReflectionHelpers.<Boolean>getField(service, "working"));
    }
    @Test public void sameTextAtWrongRowCannotReportSuccessfulArrival() {
        nativeHistory(100, false);
        service.seek(new ConversationIndex.Entry(1, "继续", 10)); advance(1);
        assertTrue(ReflectionHelpers.<Boolean>getField(service, "working"));
        assertFalse(ReflectionHelpers.<String>getField(service, "outcome").startsWith("已定位"));
    }
    @Test public void changedContentAtTargetRowFailsInsteadOfClaimingSuccess() {
        nativeHistory(19, true);
        service.seek(new ConversationIndex.Entry(1, "旧文字", 4)); advance(5);
        assertEquals(4, page);
        assertFalse(ReflectionHelpers.<Boolean>getField(service, "working"));
        assertTrue(ReflectionHelpers.<String>getField(service, "outcome").contains("内容已变化"));
    }
    @Test public void showOnScreenRequiresASecondVisibleMatch() {
        nativeHistory(4, true);
        shadowOf(text).setOnPerformActionListener((action, args) -> {
            if (action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId()) text.setText("内容在定位时变化");
            return true;
        });
        service.seek(new ConversationIndex.Entry(1, "提问 2", 4)); advance(5);
        assertFalse(ReflectionHelpers.<String>getField(service, "outcome").startsWith("已定位"));
    }
    @Test public void staleScrollEventRowIsNotAppliedToDifferentContent() {
        nativeHistory(6, true); text.setCollectionItemInfo(null);
        AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_SCROLLED);
        event.setPackageName("com.google.android.apps.bard"); shadowOf(event).setSourceNode(scroller);
        event.setFromIndex(6); event.setToIndex(6); service.onAccessibilityEvent(event);
        text.setText("另一个位置的提问");
        ScreenReader.Snapshot snapshot = ReflectionHelpers.callInstanceMethod(service, "snapshot");
        assertEquals(-1, snapshot.firstRow());
    }

    @Test public void nativeSingleRowScrollEventsProvideAnchorsWithoutCollectionItemInfo() {
        nativeHistory(11, true); text.setCollectionItemInfo(null);
        java.util.function.IntConsumer expose = row -> {
            nativeRow(row); text.setCollectionItemInfo(null);
            AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_SCROLLED);
            event.setPackageName("com.google.android.apps.bard"); shadowOf(event).setSourceNode(scroller);
            event.setFromIndex(row); event.setToIndex(row); service.onAccessibilityEvent(event);
        };
        shadowOf(scroller).setOnPerformActionListener((action, args) -> {
            if (action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_TO_POSITION.getId()) {
                expose.accept(args.getInt(AccessibilityNodeInfo.ACTION_ARGUMENT_ROW_INT)); return true;
            }
            if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD && page > 0) { expose.accept(page - 1); return true; }
            return false;
        });
        service.scan(); advance(25);
        assertEquals(6, index().entries().size());
        assertTrue(index().entries().stream().allMatch(e -> e.row() >= 0));
        assertEquals(0, page); assertFalse(ReflectionHelpers.<Boolean>getField(service, "working"));
    }
    @Test public void recycledUserRowWaitsForItsTextBeforeAdvancing() {
        nativeHistory(5, true);
        android.os.Handler loader = new android.os.Handler(Looper.getMainLooper());
        shadowOf(scroller).setOnPerformActionListener((action, args) -> {
            if (action != AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_TO_POSITION.getId()) return false;
            int row = args.getInt(AccessibilityNodeInfo.ACTION_ARGUMENT_ROW_INT);
            nativeRow(row);
            if (row % 2 == 0) {
                String content = text.getText().toString(); text.setText("");
                loader.postDelayed(() -> text.setText(content), 4200);
            }
            return true;
        });
        service.scan(); advance(35);
        assertEquals(3, index().entries().size()); assertEquals("提问 0", index().entries().get(0).text());
    }
    @Test public void changedRowIdentityPreservesSavedDirectoryInsteadOfMixingHistories() {
        nativeHistory(4, true); index().putAtRow(4, "列表改动前的提问");
        service.startScanHere(); advance(2);
        assertEquals("列表改动前的提问", index().entries().get(0).text());
        assertFalse(ReflectionHelpers.<Boolean>getField(service, "working"));
        assertTrue(ReflectionHelpers.<String>getField(service, "outcome").contains("已保留原记忆"));
    }

    @Test public void scrollEventsCannotReadRowsBeforeConsentOrFromUnapprovedGoogleHost() {
        nativeHistory(6, true); text.setCollectionItemInfo(null);
        AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_SCROLLED);
        event.setPackageName("com.google.android.apps.bard"); shadowOf(event).setSourceNode(scroller);
        event.setFromIndex(6); event.setToIndex(6);
        service.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean("consent", false).commit();
        service.onAccessibilityEvent(event);
        assertEquals(-1, (int) ReflectionHelpers.<Integer>getField(service, "eventFirst"));
        service.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean("consent", true).commit();
        root.setPackageName("com.google.android.googlequicksearchbox"); event.setPackageName("com.google.android.googlequicksearchbox");
        service.onAccessibilityEvent(event);
        assertEquals(-1, (int) ReflectionHelpers.<Integer>getField(service, "eventFirst"));
    }

    private void awaitSave() throws Exception {
        java.util.concurrent.Future<Boolean> save = ReflectionHelpers.getField(service, "pendingSave");
        if (save != null) assertTrue(save.get(5, java.util.concurrent.TimeUnit.SECONDS));
    }
    private void restartService() {
        service.onDestroy();
        service = Robolectric.buildService(NavigatorService.class).create().get();
        AccessibilityWindowInfo window = AccessibilityWindowInfo.obtain();
        shadowOf(window).setType(AccessibilityWindowInfo.TYPE_APPLICATION);
        shadowOf(window).setRoot(root); shadowOf(window).setLayer(1);
        shadowOf(service).setWindows(List.of(window)); service.onServiceConnected();
    }
    @Test public void savedScanResumesAfterServiceRestartWithoutRereadingLaterRows() throws Exception {
        nativeHistory(119, true); service.enableMemory();
        service.scan(); advance(3); service.cancel(); awaitSave();
        List<ConversationIndex.Entry> saved = List.copyOf(index().entries());
        assertTrue(saved.size() > 0 && saved.size() < 60);
        int earliest = saved.get(0).row();
        nativeRow(119); restartService();
        assertEquals(saved, index().entries());
        assertFalse(ReflectionHelpers.<Boolean>getField(service, "working"));
        java.util.List<Integer> requested = new java.util.ArrayList<>();
        shadowOf(scroller).setOnPerformActionListener((action, args) -> {
            if (action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_TO_POSITION.getId()) {
                int row = args.getInt(AccessibilityNodeInfo.ACTION_ARGUMENT_ROW_INT);
                requested.add(row); nativeRow(row); return true;
            }
            return false;
        });
        service.scan(); advance(90); awaitSave();
        assertEquals(earliest, (int) requested.get(0));
        assertTrue(requested.stream().allMatch(row -> row <= earliest));
        assertEquals(60, index().entries().size());
        assertEquals(saved.get(saved.size() - 1), index().entries().get(59));
        assertFalse(ReflectionHelpers.<Boolean>getField(service, "working"));
    }
    @Test public void separateConversationsSurviveRestartRenameSwitchAndDelete() throws Exception {
        service.enableMemory(); service.createSession("旅行计划");
        service.scan(); advance(30); awaitSave();
        String first = service.activeSessionId(); List<ConversationIndex.Entry> firstEntries = List.copyOf(index().entries());
        service.createSession("代码问题");
        text.setText("另一个会话的提问");
        shadowOf(scroller).setOnPerformActionListener((action, args) -> false);
        service.scan(); advance(15); awaitSave();
        String second = service.activeSessionId();
        assertEquals(1, index().entries().size());
        service.renameSession("代码排查"); awaitSave(); restartService();
        assertEquals(second, service.activeSessionId()); assertEquals("代码排查", service.activeSessionTitle());
        assertEquals("另一个会话的提问", index().entries().get(0).text());
        service.selectSession(first); awaitSave();
        assertEquals(firstEntries, index().entries());
        service.deleteSession(); awaitSave(); restartService();
        assertTrue(service.savedSessions().stream().noneMatch(s -> s.id().equals(first)));
        service.selectSession(second);
        assertEquals("另一个会话的提问", index().entries().get(0).text());
    }
    @Test public void wrongConversationDuringResumeDoesNotPolluteOrDeleteMemory() throws Exception {
        nativeHistory(9, true); service.enableMemory(); service.scan(); advance(25); awaitSave();
        List<ConversationIndex.Entry> saved = List.copyOf(index().entries());
        nativeRow(9);
        shadowOf(scroller).setOnPerformActionListener((action, args) -> {
            if (action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_TO_POSITION.getId()) {
                nativeRow(args.getInt(AccessibilityNodeInfo.ACTION_ARGUMENT_ROW_INT)); text.setText("另一段会话");
                return true;
            }
            return false;
        });
        service.scan(); advance(5);
        assertFalse(ReflectionHelpers.<Boolean>getField(service, "working"));
        assertEquals(saved, index().entries());
        assertTrue(ReflectionHelpers.<String>getField(service, "outcome").contains("已保留记忆"));
        restartService(); assertEquals(saved, index().entries());
    }
    @Test public void cancellationDuringResumeNeverContinuesToScan() {
        nativeHistory(19, true); index().putAtRow(0, "提问 0");
        service.scan(); advance(1); service.cancel();
        int position = page; advance(30);
        assertEquals(position, page); assertFalse(ReflectionHelpers.<Boolean>getField(service, "working"));
    }
    @Test public void memoryIsOptInAndDeletingEverythingCannotBeUndoneByQueuedSaves() throws Exception {
        service.scan(); advance(2); service.cancel();
        assertFalse(service.remembersSessions()); restartService();
        assertTrue(index().entries().isEmpty());
        service.enableMemory(); service.createSession("待删除");
        service.scan(); advance(2); service.deleteAllMemory(); awaitSave();
        restartService(); assertTrue(index().entries().isEmpty()); assertFalse(service.remembersSessions());
        SessionStore disk = new SessionStore(service, true);
        assertEquals(1, disk.sessions().size()); assertTrue(disk.active().entries().isEmpty());
    }
    @Test public void collectingAutomaticallyCheckpointsWithoutWaitingForStop() throws Exception {
        nativeHistory(119, true); service.enableMemory(); service.scan(); advance(4); awaitSave();
        assertTrue(ReflectionHelpers.<Boolean>getField(service, "working"));
        SessionStore disk = new SessionStore(service, true);
        assertTrue(disk.active().entries().size() > 0);
        assertTrue(disk.active().entries().size() <= index().entries().size());
        assertEquals("提问 59", disk.active().entries().get(disk.active().entries().size() - 1).text());
    }

    private android.widget.Button findButton(android.view.View view, String label) {
        if (view instanceof android.widget.Button && ((android.widget.Button) view).getText().toString().equals(label)) return (android.widget.Button) view;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                android.widget.Button found = findButton(group.getChildAt(i), label);
                if (found != null) return found;
            }
        }
        return null;
    }
    private android.widget.EditText findEditor(android.view.View view) {
        if (view instanceof android.widget.EditText) return (android.widget.EditText) view;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                android.widget.EditText found = findEditor(group.getChildAt(i)); if (found != null) return found;
            }
        }
        return null;
    }
    @Test public void sidebarMemoryOptInAndSessionMenuAreUsable() throws Exception {
        Sidebar sidebar = ReflectionHelpers.getField(service, "sidebar"); sidebar.open();
        android.view.View panel = ReflectionHelpers.getField(sidebar, "root");
        findButton(panel, "启用本机会话记忆").performClick();
        android.app.AlertDialog consent = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
        assertTrue(consent.isShowing()); assertFalse(service.remembersSessions());
        consent.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick(); shadowOf(Looper.getMainLooper()).idle();
        assertTrue(service.remembersSessions());
        panel = ReflectionHelpers.getField(sidebar, "root"); findButton(panel, "会话").performClick();
        android.app.AlertDialog menu = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
        shadowOf(menu).clickOnItem(0); shadowOf(Looper.getMainLooper()).idle();
        android.app.AlertDialog editor = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
        findEditor(editor.getWindow().getDecorView()).setText("侧栏创建的会话");
        editor.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick(); shadowOf(Looper.getMainLooper()).idle(); awaitSave();
        assertEquals("侧栏创建的会话", service.activeSessionTitle());
        assertEquals(2, new SessionStore(service, true).sessions().size());
        assertFalse(ReflectionHelpers.<Boolean>getField(service, "working"));
    }

    @Test public void unresponsiveGeminiReceivesFewerRequestsAndResumesFromSavedPrompt() throws Exception {
        nativeHistory(19, true); service.enableMemory();
        final boolean[] frozen = {true}; final int[] requests = {0};
        shadowOf(scroller).setOnPerformActionListener((action, args) -> {
            requests[0]++;
            if (frozen[0]) return true;
            if (action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_TO_POSITION.getId()) {
                nativeRow(args.getInt(AccessibilityNodeInfo.ACTION_ARGUMENT_ROW_INT)); return true;
            }
            return false;
        });
        nativeRow(18); service.scan(); advance(20); awaitSave();
        assertFalse(ReflectionHelpers.<Boolean>getField(service, "working"));
        assertTrue("back off instead of repeatedly pushing a frozen app", requests[0] <= 14);
        assertEquals(1, index().entries().size());
        frozen[0] = false; nativeRow(19); restartService(); service.scan(); advance(30);
        assertEquals(10, index().entries().size()); assertEquals(0, page);
    }
    @Test public void collectingNewerPromptsRetainsEarlierSavedHistory() throws Exception {
        nativeHistory(9, true); service.enableMemory(); service.scan(); advance(25); awaitSave();
        List<ConversationIndex.Entry> original = List.copyOf(index().entries());
        nativeHistory(15, true); service.startScanHere(); advance(25); awaitSave();
        assertEquals(8, index().entries().size());
        assertEquals(original, index().entries().subList(0, 5));
        assertEquals("提问 7", index().entries().get(7).text());
        restartService(); assertEquals(8, index().entries().size());
    }

    @Test public void changingRowMetadataCannotDiscardEarlierOrLaterSavedPrompts() throws Exception {
        nativeHistory(9, true); service.enableMemory(); service.scan(); advance(25); awaitSave();
        List<ConversationIndex.Entry> saved = List.copyOf(index().entries());
        nativeHistory(11, true); nativeRow(10); text.setCollectionItemInfo(null);
        shadowOf(scroller).setOnPerformActionListener((action, args) -> false);
        service.startScanHere(); advance(15); awaitSave();
        assertEquals(6, index().entries().size());
        for (ConversationIndex.Entry e : saved) assertTrue(index().entries().stream().anyMatch(current -> current.id() == e.id() && current.text().equals(e.text())));
        nativeHistory(13, true); service.startScanHere(); advance(25); awaitSave();
        assertEquals(7, index().entries().size());
        restartService(); assertEquals(7, index().entries().size());
        assertEquals("提问 0", index().entries().get(0).text());
        assertEquals("继续", index().entries().get(5).text());
        assertEquals("提问 6", index().entries().get(6).text());
        assertTrue(index().entries().stream().allMatch(e -> e.row() < 0));
    }

    @Test public void supplementingTextOnlyHistoryKeepsNewerAndOlderPromptsInOrder() {
        service.scan(); advance(30);
        page = 12; text.setText("段落 12");
        shadowOf(scroller).setOnPerformActionListener((action, args) -> {
            if (action != AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD || page <= 0) return false;
            text.setText("段落 " + --page); return true;
        });
        service.startScanHere(); advance(30);
        assertEquals(13, index().entries().size());
        for (int i = 0; i < 13; i++) assertEquals("段落 " + i, index().entries().get(i).text());
    }
}
