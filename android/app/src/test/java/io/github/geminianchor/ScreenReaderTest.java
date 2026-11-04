package io.github.geminianchor;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 35})
public class ScreenReaderTest {
    private AccessibilityNodeInfo node(String text, String clazz, int top, int height) {
        AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain();
        node.setText(text); node.setClassName(clazz); node.setVisibleToUser(true);
        node.setBoundsInScreen(new Rect(0, top, 1080, top + height));
        return node;
    }
    @Test public void selectsMainScrollerAndExcludesInputsButtonsAndHiddenText() {
        AccessibilityNodeInfo root = node(null, "FrameLayout", 0, 1920);
        AccessibilityNodeInfo chat = node(null, "ScrollView", 200, 1400); chat.setScrollable(true);
        shadowOf(root).addChild(chat);
        AccessibilityNodeInfo prompt = node("我的问题", "TextView", 250, 70);
        prompt.setBoundsInScreen(new Rect(400, 250, 1020, 320));
        AccessibilityNodeInfo response = node("回答", "TextView", 350, 300);
        AccessibilityNodeInfo input = node("草稿不应读取", "EditText", 700, 70); input.setEditable(true);
        AccessibilityNodeInfo password = node("密码不应读取", "TextView", 800, 70); password.setPassword(true);
        AccessibilityNodeInfo hidden = node("隐藏不应读取", "TextView", 900, 70); hidden.setVisibleToUser(false);
        shadowOf(chat).addChild(prompt); shadowOf(chat).addChild(response);
        shadowOf(chat).addChild(input); shadowOf(chat).addChild(password); shadowOf(chat).addChild(hidden);
        shadowOf(chat).addChild(node("编辑", "Button", 1000, 60));
        ScreenReader.Snapshot snapshot = ScreenReader.read(root);
        assertEquals(chat, snapshot.scroller());
        assertEquals(java.util.List.of("我的问题"), snapshot.texts());
    }
    @Test public void parentTextIsNotRepeatedByChildrenAndNoScrollerMeansNoIndex() {
        AccessibilityNodeInfo root = node(null, "ScrollView", 0, 1800); root.setScrollable(true);
        AccessibilityNodeInfo parent = node("重复暴露的提问", "View", 200, 200);
        parent.setViewIdResourceName("test:id/user_query");
        shadowOf(parent).addChild(node("重复暴露的提问", "TextView", 210, 100));
        shadowOf(root).addChild(parent);
        assertEquals(1, ScreenReader.read(root).texts().size());
        root.setScrollable(false); assertTrue(ScreenReader.read(root).texts().isEmpty());
    }
    @Test public void packageGuardRequiresExplicitOptInForGoogleHost() {
        AccessibilityNodeInfo root = node("Gemini", "TextView", 0, 1800);
        root.setPackageName("org.example.other"); assertFalse(ScreenReader.allowed(root, true));
        root.setPackageName("com.google.android.apps.bard"); assertTrue(ScreenReader.allowed(root, false));
        root.setPackageName("com.google.android.googlequicksearchbox");
        assertFalse(ScreenReader.allowed(root, false)); assertTrue(ScreenReader.allowed(root, true));
        root.setText("Google 搜索"); assertFalse(ScreenReader.allowed(root, true));
    }
    @Test public void paragraphsWithinOneMessageAreOneDirectoryEntry() {
        AccessibilityNodeInfo root = node(null, "ScrollView", 0, 1800); root.setScrollable(true);
        AccessibilityNodeInfo message = node(null, "ViewGroup", 200, 700);
        message.setViewIdResourceName("com.google.android.apps.bard:id/user_query");
        shadowOf(message).addChild(node("一个很长的问题，第一段。", "TextView", 220, 180));
        shadowOf(message).addChild(node("还是同一个问题，第二段。", "TextView", 420, 180));
        shadowOf(message).addChild(node("复制", "Button", 800, 60));
        shadowOf(root).addChild(message);
        AccessibilityNodeInfo next = node("另一条问题", "TextView", 1000, 100);
        next.setViewIdResourceName("com.google.android.apps.bard:id/user_query");
        shadowOf(root).addChild(next);
        assertEquals(java.util.List.of("一个很长的问题，第一段。 还是同一个问题，第二段。", "另一条问题"), ScreenReader.read(root).texts());
    }
    @Test public void messageParentDoesNotHideDifferentChildText() {
        AccessibilityNodeInfo root = node(null, "ScrollView", 0, 1800); root.setScrollable(true);
        AccessibilityNodeInfo message = node("问题第一段", "ViewGroup", 200, 600);
        message.setViewIdResourceName("test:id/user_query");
        message.setCollectionItemInfo(AccessibilityNodeInfo.CollectionItemInfo.obtain(0, 1, 0, 1, false));
        shadowOf(message).addChild(node("问题第一段", "TextView", 220, 100));
        shadowOf(message).addChild(node("问题第二段", "TextView", 380, 100));
        shadowOf(root).addChild(message);
        assertEquals(java.util.List.of("问题第一段 问题第二段"), ScreenReader.read(root).texts());
    }
    @Test public void supportsDescriptionOnlyMessageWithoutIndexingActionLabels() {
        AccessibilityNodeInfo root = node(null, "ScrollView", 0, 1800); root.setScrollable(true);
        AccessibilityNodeInfo message = node(null, "TextView", 200, 600);
        message.setContentDescription("通过无障碍描述暴露的完整提问");
        message.setBoundsInScreen(new Rect(250, 200, 1020, 800));
        shadowOf(root).addChild(message);
        AccessibilityNodeInfo action = node(null, "ImageButton", 820, 60); action.setContentDescription("复制提问");
        shadowOf(root).addChild(action);
        assertEquals(java.util.List.of("通过无障碍描述暴露的完整提问"), ScreenReader.read(root).texts());
    }

    @Test public void reportedLongCenteredReplyIsNotAUserPromptAndOuterHistoryWins() {
        AccessibilityNodeInfo history = node(null, "android.support.v7.widget.RecyclerView", 0, 2800);
        history.setBoundsInScreen(new Rect(0, 0, 1260, 2800));
        history.setViewIdResourceName("com.google.android.googlequicksearchbox:id/assistant_robin_chat_history_list");
        history.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_TO_POSITION);
        AccessibilityNodeInfo message = node(null, "android.view.ViewGroup", 0, 2800);
        message.setBoundsInScreen(new Rect(84, 0, 1176, 2800));
        message.setViewIdResourceName("com.google.android.googlequicksearchbox:id/assistant_robin_content_message_container");
        message.setCollectionItemInfo(AccessibilityNodeInfo.CollectionItemInfo.obtain(27, 1, 0, 1, false));
        AccessibilityNodeInfo nested = node(null, "android.support.v7.widget.RecyclerView", 0, 2800);
        nested.setBoundsInScreen(new Rect(84, 0, 1176, 2800));
        nested.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_TO_POSITION);
        nested.setViewIdResourceName("com.google.android.googlequicksearchbox:id/assistant_robin_message_content");
        AccessibilityNodeInfo reply = node("长".repeat(4174), "android.widget.TextView", 0, 2800);
        reply.setBoundsInScreen(new Rect(84, 0, 1176, 2800));
        reply.setViewIdResourceName("com.google.android.googlequicksearchbox:id/assistant_robin_text");
        shadowOf(history).addChild(message); shadowOf(message).addChild(nested); shadowOf(nested).addChild(reply);
        ScreenReader.Snapshot snapshot = ScreenReader.read(history);
        assertEquals(history, snapshot.scroller()); assertTrue(snapshot.texts().isEmpty());
        assertEquals(27, snapshot.firstRow()); assertEquals(1, snapshot.messages().size());
        assertTrue(ScreenReader.diagnostics(history).contains("positionAction=true"));
        assertTrue(ScreenReader.diagnostics(history).contains("row=27"));
    }
    @Test public void fullWidthRowWrapsOneRightAlignedMultilineUserBubble() {
        AccessibilityNodeInfo history = node(null, "RecyclerView", 0, 1800); history.setScrollable(true);
        AccessibilityNodeInfo row = node(null, "ViewGroup", 200, 500);
        row.setCollectionItemInfo(AccessibilityNodeInfo.CollectionItemInfo.obtain(8, 1, 0, 1, false));
        AccessibilityNodeInfo bubble = node(null, "ViewGroup", 200, 500);
        bubble.setBoundsInScreen(new Rect(230, 200, 1020, 700));
        shadowOf(bubble).addChild(node("第一段", "TextView", 220, 100));
        shadowOf(bubble).addChild(node("第二段", "TextView", 340, 100));
        shadowOf(row).addChild(bubble); shadowOf(history).addChild(row);
        ScreenReader.Snapshot snapshot = ScreenReader.read(history);
        assertEquals(java.util.List.of("第一段 第二段"), snapshot.texts());
        assertEquals(8, snapshot.blocks().get(0).row());
    }
    @Test public void explicitAssistantRoleOverridesRightAlignedGeometry() {
        AccessibilityNodeInfo root = node(null, "ScrollView", 0, 1800); root.setScrollable(true);
        AccessibilityNodeInfo reply = node("缩进回复", "TextView", 200, 500);
        reply.setViewIdResourceName("test:id/model_response");
        reply.setBoundsInScreen(new Rect(500, 200, 1020, 700)); shadowOf(root).addChild(reply);
        assertTrue(ScreenReader.read(root).texts().isEmpty());
    }
    @Test public void indentedCodeInsideNativeReplyDoesNotBecomeASeparateUserEntry() {
        AccessibilityNodeInfo root = node(null, "RecyclerView", 0, 1800); root.setScrollable(true);
        AccessibilityNodeInfo reply = node(null, "ViewGroup", 200, 1000);
        reply.setViewIdResourceName("test:id/assistant_robin_content_message_container");
        reply.setBoundsInScreen(new Rect(60, 200, 1020, 1200));
        shadowOf(reply).addChild(node("回复正文", "TextView", 210, 300));
        AccessibilityNodeInfo code = node("缩进代码", "TextView", 600, 300);
        code.setBoundsInScreen(new Rect(500, 600, 1020, 900));
        shadowOf(reply).addChild(code); shadowOf(root).addChild(reply);
        assertTrue(ScreenReader.read(root).texts().isEmpty());
    }
}
