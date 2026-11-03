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
    @Test public void scansEarlierPagesInOrderReturnsAndNavigatesToOldContent() {
        service.scan(); advance(30);
        assertEquals(10, index().entries().size());
        assertEquals("段落 0", index().entries().get(0).text());
        assertEquals("段落 9", index().entries().get(9).text());
        assertEquals("scan must return to the starting page", 9, page);
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
    @Test public void leavingGeminiStopsScrollingAndClearsPrivateText() {
        service.scan(); advance(2);
        root.setPackageName("org.example.other");
        AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
        event.setPackageName("org.example.other"); service.onAccessibilityEvent(event); advance(1);
        int actions = scrolls; advance(10);
        assertTrue(index().entries().isEmpty()); assertEquals(actions, scrolls);
        assertFalse(ReflectionHelpers.<Boolean>getField(service, "working"));
    }
    @Test public void missingTargetStopsAtBoundsInsteadOfScrollingForever() {
        service.seek(new ConversationIndex.Entry(-1, "没有这个内容")); advance(40);
        assertFalse(ReflectionHelpers.<Boolean>getField(service, "working"));
        int actions = scrolls; advance(20); assertEquals(actions, scrolls);
    }
}
