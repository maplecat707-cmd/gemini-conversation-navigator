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
        if (excluded(node, depth)) return;
        List<Block> children = new ArrayList<>();
        for (int i = 0; i < node.getChildCount(); i++) collect(node.getChild(i), children, depth + 1);
        String text = ConversationIndex.normalize(node.getText() == null ? "" : node.getText().toString());
        // Some native/Compose messages expose their text only as a description.
        // Do not turn a clickable icon's accessibility label into conversation text.
        boolean readableDescription = String.valueOf(node.getClassName()).endsWith("TextView")
                || messageBoundary(node) || (!node.isClickable() && !node.isLongClickable());
        if (text.isEmpty() && children.isEmpty() && readableDescription) {
            text = ConversationIndex.normalize(node.getContentDescription() == null ? "" : node.getContentDescription().toString());
        }
        Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
        String descendants = ConversationIndex.normalize(String.join(" ", children.stream().map(Block::text).collect(java.util.stream.Collectors.toList())));
        if (!text.isEmpty() && !bounds.isEmpty()) {
            if (descendants.isEmpty() || !descendants.contains(text)) {
                // A parent may repeat the complete text, or only expose its first
                // paragraph. Preserve different child content in the latter case.
                if (!descendants.isEmpty() && text.contains(descendants)) children.clear();
                children.add(0, new Block(text, bounds, node));
            }
        }
        if (messageBoundary(node) && !children.isEmpty()) {
            String joined = ConversationIndex.normalize(String.join(" ", children.stream().map(Block::text).collect(java.util.stream.Collectors.toList())));
            if (bounds.isEmpty()) children.forEach(block -> bounds.union(block.bounds()));
            result.add(new Block(joined, bounds, node));
        } else result.addAll(children);
    }
    private static boolean excluded(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 45 || !node.isVisibleToUser() || node.isEditable() || node.isPassword()) return true;
        String clazz = String.valueOf(node.getClassName());
        String id = String.valueOf(node.getViewIdResourceName()).toLowerCase(Locale.ROOT);
        return clazz.contains("Button") || clazz.contains("EditText") || node.isCheckable()
                || id.contains("toolbar") || id.contains("navigation") || id.contains("input");
    }
    private static boolean messageBoundary(AccessibilityNodeInfo node) {
        if (node.isScrollable()) return false;
        if (node.getCollectionItemInfo() != null) return true;
        String id = String.valueOf(node.getViewIdResourceName()).toLowerCase(Locale.ROOT).replace('-', '_');
        String clazz = String.valueOf(node.getClassName()).toLowerCase(Locale.ROOT).replace('-', '_');
        return id.matches(".*(?:[:/_])(?:user_query|user_message|model_response|assistant_message|message_content|message_bubble|chat_turn|conversation_turn)(?:_container)?$")
                || clazz.equals("user_query") || clazz.equals("model_response");
    }
    static boolean scroll(AccessibilityNodeInfo node, int direction) {
        int primary = direction < 0 ? AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD : AccessibilityNodeInfo.ACTION_SCROLL_FORWARD;
        int alternative = direction < 0 ? AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.getId()
                : AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.getId();
        if (node.performAction(primary)) return true;
        return node.getActionList().stream().anyMatch(action -> action.getId() == alternative) && node.performAction(alternative);
    }
    static String diagnostics(AccessibilityNodeInfo root) {
        Snapshot screen = read(root);
        StringBuilder report = new StringBuilder("Gemini Anchor node structure (no conversation text)\n");
        report.append("readableEntries=").append(screen.blocks().size()).append("\n");
        if (screen.scroller() == null) return report.append("scrollContainer=not-found\n").toString();
        appendStructure(screen.scroller(), report, 0, new int[]{0});
        return report.toString();
    }
    private static void appendStructure(AccessibilityNodeInfo node, StringBuilder report, int depth, int[] count) {
        if (node == null || depth > 45 || count[0] >= 160) return;
        int ordinal = count[0]++;
        Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
        String id = String.valueOf(node.getViewIdResourceName());
        // Include only conventional resource identifiers, never dynamic web IDs.
        if (!id.matches("[a-zA-Z0-9_.]+:id/[a-zA-Z_][a-zA-Z0-9_]*")) id = "-";
        String clazz = String.valueOf(node.getClassName());
        if (!clazz.matches("[a-zA-Z0-9_.$]+")) clazz = "custom";
        report.append(ordinal).append(" depth=").append(depth).append(" class=").append(clazz)
                .append(" id=").append(id).append(" children=").append(node.getChildCount())
                .append(" textChars=").append(node.getText() == null ? 0 : node.getText().length())
                .append(" descriptionChars=").append(node.getContentDescription() == null ? 0 : node.getContentDescription().length())
                .append(" visible=").append(node.isVisibleToUser()).append(" message=").append(messageBoundary(node))
                .append(" scrollable=").append(node.isScrollable()).append(" bounds=").append(bounds.toShortString())
                .append(" actions=").append(node.getActionList().stream().map(a -> String.valueOf(a.getId())).collect(java.util.stream.Collectors.joining(","))).append("\n");
        // Do not inspect input/password subtrees even for diagnostics.
        if (!node.isEditable() && !node.isPassword()) {
            for (int i = 0; i < node.getChildCount(); i++) appendStructure(node.getChild(i), report, depth + 1, count);
        }
        if (depth == 0 && count[0] >= 160) report.append("truncatedAt=160 nodes\n");
    }
}
