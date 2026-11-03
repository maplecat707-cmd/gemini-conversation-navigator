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
        AccessibilityNodeInfo response = node("回答", "TextView", 350, 300);
        AccessibilityNodeInfo input = node("草稿不应读取", "EditText", 700, 70); input.setEditable(true);
        AccessibilityNodeInfo password = node("密码不应读取", "TextView", 800, 70); password.setPassword(true);
        AccessibilityNodeInfo hidden = node("隐藏不应读取", "TextView", 900, 70); hidden.setVisibleToUser(false);
        shadowOf(chat).addChild(prompt); shadowOf(chat).addChild(response);
        shadowOf(chat).addChild(input); shadowOf(chat).addChild(password); shadowOf(chat).addChild(hidden);
        shadowOf(chat).addChild(node("编辑", "Button", 1000, 60));
        ScreenReader.Snapshot snapshot = ScreenReader.read(root);
        assertEquals(chat, snapshot.scroller());
        assertEquals(java.util.List.of("我的问题", "回答"), snapshot.texts());
    }
    @Test public void parentTextIsNotRepeatedByChildrenAndNoScrollerMeansNoIndex() {
        AccessibilityNodeInfo root = node(null, "ScrollView", 0, 1800); root.setScrollable(true);
        AccessibilityNodeInfo parent = node("重复暴露的提问", "View", 200, 200);
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
}
