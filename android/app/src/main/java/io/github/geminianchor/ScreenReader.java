package io.github.geminianchor;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import io.github.geminianchor.core.ConversationIndex;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** No coordinates, OCR or guessed taps: only exposed accessibility text/actions. */
final class ScreenReader {
    record Block(String text, Rect bounds, AccessibilityNodeInfo node) {}
    record Snapshot(AccessibilityNodeInfo scroller, List<Block> blocks) {
        List<String> texts() { return blocks.stream().map(Block::text).collect(java.util.stream.Collectors.toList()); }
        String fingerprint() { return String.join("\n", texts()); }
    }
    private static final String BARD = "com.google.android.apps.bard";
    private static final String GOOGLE = "com.google.android.googlequicksearchbox";
    static boolean targetPackage(CharSequence name) { return BARD.contentEquals(name == null ? "" : name) || GOOGLE.contentEquals(name == null ? "" : name); }
    static boolean allowed(AccessibilityNodeInfo root, boolean googleHost) {
        if (root == null) return false;
        String pkg = String.valueOf(root.getPackageName());
        return BARD.equals(pkg) || (GOOGLE.equals(pkg) && googleHost && hasGeminiMarker(root, 0));
    }
    private static boolean hasGeminiMarker(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 35) return false;
        if (String.valueOf(node.getText()).toLowerCase(Locale.ROOT).contains("gemini")
                || String.valueOf(node.getContentDescription()).toLowerCase(Locale.ROOT).contains("gemini")) return true;
        for (int i = 0; i < node.getChildCount(); i++) if (hasGeminiMarker(node.getChild(i), depth + 1)) return true;
        return false;
    }
    static Snapshot read(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> candidates = new ArrayList<>(); findScrollers(root, candidates, 0);
        AccessibilityNodeInfo best = candidates.stream().max(Comparator.comparingLong(ScreenReader::area)).orElse(null);
        List<Block> blocks = new ArrayList<>();
        if (best != null) collect(best, blocks, 0);
        blocks.sort(Comparator.comparingInt((Block b) -> b.bounds.top).thenComparingInt(b -> b.bounds.left));
        return new Snapshot(best, blocks);
    }
    private static long area(AccessibilityNodeInfo node) {
        Rect r = new Rect(); node.getBoundsInScreen(r); return (long) r.width() * r.height();
    }
    private static void findScrollers(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> result, int depth) {
        if (node == null || depth > 45 || !node.isVisibleToUser()) return;
        Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
        if (node.isScrollable() && bounds.height() > 160 && bounds.width() > 160) result.add(node);
        for (int i = 0; i < node.getChildCount(); i++) findScrollers(node.getChild(i), result, depth + 1);
    }
    private static void collect(AccessibilityNodeInfo node, List<Block> result, int depth) {
        if (node == null || depth > 45 || !node.isVisibleToUser() || node.isEditable() || node.isPassword()) return;
        String clazz = String.valueOf(node.getClassName());
        String id = String.valueOf(node.getViewIdResourceName()).toLowerCase(Locale.ROOT);
        if (clazz.contains("Button") || clazz.contains("EditText") || id.contains("toolbar") || id.contains("navigation") || id.contains("input")) return;
        String text = ConversationIndex.normalize(node.getText() == null ? "" : node.getText().toString());
        if (!text.isEmpty()) {
            Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
            if (!bounds.isEmpty()) result.add(new Block(text, bounds, node));
            return; // A parent can expose the same text as its children.
        }
        // Content descriptions are usually button labels, not conversation text.
        for (int i = 0; i < node.getChildCount(); i++) collect(node.getChild(i), result, depth + 1);
    }
}
