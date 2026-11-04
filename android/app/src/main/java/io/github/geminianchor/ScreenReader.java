package io.github.geminianchor;

import android.graphics.Rect;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;
import io.github.geminianchor.core.ConversationIndex;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/** Reads message boundaries and coordinates, not screenshots or conversation APIs. */
final class ScreenReader {
    enum Role { USER, ASSISTANT, UNKNOWN }
    record Block(String text, Rect bounds, AccessibilityNodeInfo node, int row, Role role) {}
    record Snapshot(AccessibilityNodeInfo scroller, List<Block> messages, Rect viewport) {
        List<Block> blocks() { return messages.stream().filter(b -> b.role() == Role.USER && !b.text().isEmpty()).collect(Collectors.toList()); }
        List<String> texts() { return blocks().stream().map(Block::text).collect(Collectors.toList()); }
        int firstRow() { return messages.stream().mapToInt(Block::row).filter(r -> r >= 0).min().orElse(-1); }
        int lastRow() { return messages.stream().mapToInt(Block::row).filter(r -> r >= 0).max().orElse(-1); }
        String fingerprint() {
            return messages.stream().map(b -> b.row() + ":" + b.role() + ":" + b.text().hashCode() + ":" + b.bounds()).collect(Collectors.joining("|"));
        }
        boolean hasRow(int row) { return row >= 0 && messages.stream().anyMatch(b -> b.row() == row); }
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
        if (node == null || depth > 45) return false;
        // Native chat marker supplied by the device report, including views without a visible title.
        if (resource(node).equals("assistant_robin_chat_history_list")
                || String.valueOf(node.getText()).toLowerCase(Locale.ROOT).contains("gemini")
                || String.valueOf(node.getContentDescription()).toLowerCase(Locale.ROOT).contains("gemini")) return true;
        for (int i = 0; i < node.getChildCount(); i++) if (hasGeminiMarker(node.getChild(i), depth + 1)) return true;
        return false;
    }
    static String resource(AccessibilityNodeInfo node) {
        String id = String.valueOf(node.getViewIdResourceName()).toLowerCase(Locale.ROOT);
        return id.substring(id.lastIndexOf('/') + 1).replace('-', '_');
    }
    static Snapshot read(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> candidates = new ArrayList<>(); findScrollers(root, candidates, 0);
        AccessibilityNodeInfo best = candidates.stream().max(Comparator.comparingLong(n ->
                resource(n).equals("assistant_robin_chat_history_list") ? Long.MAX_VALUE : area(n))).orElse(null);
        Rect viewport = new Rect();
        List<Block> messages = new ArrayList<>();
        if (best != null) {
            best.getBoundsInScreen(viewport);
            for (int i = 0; i < best.getChildCount(); i++) collectMessage(best.getChild(i), messages, viewport, -1, 0);
        }
        messages.sort(Comparator.comparingInt((Block b) -> b.bounds.top).thenComparingInt(b -> b.bounds.left));
        return new Snapshot(best, messages, viewport);
    }
    private static long area(AccessibilityNodeInfo node) { Rect r = bounds(node); return (long) r.width() * r.height(); }
    private static Rect bounds(AccessibilityNodeInfo node) { Rect r = new Rect(); node.getBoundsInScreen(r); return r; }
    private static void findScrollers(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> result, int depth) {
        if (node == null || depth > 45 || !node.isVisibleToUser()) return;
        Rect r = bounds(node);
        if ((node.isScrollable() || resource(node).equals("assistant_robin_chat_history_list") || supportsPosition(node)) && r.height() > 160 && r.width() > 160) result.add(node);
        for (int i = 0; i < node.getChildCount(); i++) findScrollers(node.getChild(i), result, depth + 1);
    }
    private static boolean excluded(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 45 || !node.isVisibleToUser() || node.isEditable() || node.isPassword()) return true;
        String clazz = String.valueOf(node.getClassName()), id = resource(node);
        return clazz.contains("Button") || clazz.contains("EditText") || node.isCheckable()
                || id.contains("toolbar") || id.contains("navigation") || id.contains("input") || id.contains("progress_bar");
    }
    private static Role explicitRole(AccessibilityNodeInfo node) {
        String id = resource(node);
        if (id.matches("(?:.*_)?(?:user_query|user_message|user_prompt|query_bubble|human_message)(?:_(?:container|text|content))?")) return Role.USER;
        if (id.matches("(?:.*_)?(?:model_response|assistant_message|model_message|response_text)(?:_(?:container|text|content))?")) return Role.ASSISTANT;
        return Role.UNKNOWN;
    }
    private static Role nestedRole(AccessibilityNodeInfo node, int depth) {
        if (excluded(node, depth)) return Role.UNKNOWN;
        Role role = explicitRole(node);
        if (role != Role.UNKNOWN) return role;
        for (int i = 0; i < node.getChildCount(); i++) {
            Role child = nestedRole(node.getChild(i), depth + 1);
            if (child == Role.UNKNOWN) continue;
            if (role != Role.UNKNOWN && role != child) return Role.UNKNOWN;
            role = child;
        }
        return role;
    }
    private static boolean boundary(AccessibilityNodeInfo node) {
        if (node.isScrollable()) return false;
        String id = resource(node);
        return node.getCollectionItemInfo() != null || explicitRole(node) != Role.UNKNOWN
                || id.equals("assistant_robin_content_message_container")
                || id.matches("(?:.*_)?(?:message_bubble|chat_turn|conversation_turn)(?:_container)?");
    }
    private static boolean rightBubble(Rect r, Rect viewport) {
        if (r.isEmpty() || viewport.isEmpty()) return false;
        int left = r.left - viewport.left, right = viewport.right - r.right, width = viewport.width();
        // Centered full-width replies in the report have equal 84 px margins.
        return left >= width * .10 && right >= -width * .02 && right <= width * .18
                && left - right >= width * .07 && r.width() <= width * .88;
    }
    private static void collectMessage(AccessibilityNodeInfo node, List<Block> result, Rect viewport, int inheritedRow, int depth) {
        if (excluded(node, depth)) return;
        int row = node.getCollectionItemInfo() == null ? inheritedRow : node.getCollectionItemInfo().getRowIndex();
        Rect rect = bounds(node);
        if (boundary(node) || node.getChildCount() == 0 || rightBubble(rect, viewport)) {
            String text = readText(node, 0);
            Role role = nestedRole(node, 0);
            if (role == Role.UNKNOWN && rightBubble(rect, viewport)) role = Role.USER;
            // A full-width row can wrap one right-aligned bubble. Only inspect its
            // immediate children, never isolated indented code inside a reply.
            if (role == Role.UNKNOWN && rect.width() >= viewport.width() * .95) {
                for (int i = 0; i < node.getChildCount(); i++) {
                    AccessibilityNodeInfo child = node.getChild(i);
                    if (!excluded(child, 0) && rightBubble(bounds(child), viewport) && readText(child, 0).equals(text) && !text.isEmpty()) {
                        role = Role.USER; break;
                    }
                }
            }
            if (!rect.isEmpty() && (!text.isEmpty() || row >= 0 || role == Role.USER)) result.add(new Block(text, rect, node, row, role));
            return;
        }
        for (int i = 0; i < node.getChildCount(); i++) collectMessage(node.getChild(i), result, viewport, row, depth + 1);
    }
    private static String readText(AccessibilityNodeInfo node, int depth) {
        if (excluded(node, depth)) return "";
        List<String> children = new ArrayList<>();
        for (int i = 0; i < node.getChildCount(); i++) { String s = readText(node.getChild(i), depth + 1); if (!s.isEmpty()) children.add(s); }
        String own = ConversationIndex.normalize(node.getText() == null ? "" : node.getText().toString());
        String joined = ConversationIndex.normalize(String.join(" ", children));
        if (own.isEmpty() && joined.isEmpty() && (boundary(node) || String.valueOf(node.getClassName()).endsWith("TextView")))
            own = ConversationIndex.normalize(node.getContentDescription() == null ? "" : node.getContentDescription().toString());
        if (own.isEmpty() || joined.contains(own)) return joined;
        if (joined.isEmpty() || own.contains(joined)) return own;
        return own + " " + joined;
    }
    static boolean supportsPosition(AccessibilityNodeInfo node) {
        return node.getActionList().stream().anyMatch(a -> a.getId() == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_TO_POSITION.getId());
    }
    static boolean scrollToRow(AccessibilityNodeInfo node, int row) {
        if (row < 0 || !supportsPosition(node)) return false;
        Bundle args = new Bundle(); args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_ROW_INT, row);
        args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_COLUMN_INT, 0);
        return node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_TO_POSITION.getId(), args);
    }
    static boolean scroll(AccessibilityNodeInfo node, int direction) {
        int primary = direction < 0 ? AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD : AccessibilityNodeInfo.ACTION_SCROLL_FORWARD;
        int alternate = direction < 0 ? AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.getId() : AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.getId();
        if (node.performAction(primary)) return true;
        return node.getActionList().stream().anyMatch(a -> a.getId() == alternate) && node.performAction(alternate);
    }
    static String diagnostics(AccessibilityNodeInfo root) {
        Snapshot screen = read(root);
        StringBuilder report = new StringBuilder("Gemini Anchor structure (no conversation text)\n");
        report.append("userPrompts=").append(screen.blocks().size()).append(" visibleMessages=").append(screen.messages().size())
                .append(" firstRow=").append(screen.firstRow()).append(" lastRow=").append(screen.lastRow()).append("\n");
        if (screen.scroller() == null) return report.append("scrollContainer=not-found\n").toString();
        report.append("positionAction=").append(supportsPosition(screen.scroller()))
                .append(" totalRows=").append(screen.scroller().getCollectionInfo() == null ? -1 : screen.scroller().getCollectionInfo().getRowCount()).append("\n");
        appendStructure(screen.scroller(), report, 0, new int[]{0}, screen.viewport());
        return report.toString();
    }
    private static void appendStructure(AccessibilityNodeInfo node, StringBuilder report, int depth, int[] count, Rect viewport) {
        if (node == null || depth > 45 || count[0] >= 120) return;
        String id = String.valueOf(node.getViewIdResourceName());
        if (!id.matches("[a-zA-Z0-9_.]+:id/[a-zA-Z_][a-zA-Z0-9_]*")) id = "-";
        String clazz = String.valueOf(node.getClassName()); if (!clazz.matches("[a-zA-Z0-9_.$]+")) clazz = "custom";
        Rect r = bounds(node);
        report.append(count[0]++).append(" d=").append(depth).append(" class=").append(clazz).append(" id=").append(id)
                .append(" row=").append(node.getCollectionItemInfo() == null ? -1 : node.getCollectionItemInfo().getRowIndex())
                .append(" role=").append(explicitRole(node)).append(" right=").append(rightBubble(r, viewport))
                .append(" textChars=").append(node.getText() == null ? 0 : node.getText().length())
                .append(" visible=").append(node.isVisibleToUser()).append(" bounds=").append(r.toShortString())
                .append(" actions=").append(node.getActionList().stream().map(a -> String.valueOf(a.getId())).collect(Collectors.joining(","))).append("\n");
        if (!node.isEditable() && !node.isPassword()) for (int i = 0; i < node.getChildCount(); i++) appendStructure(node.getChild(i), report, depth + 1, count, viewport);
        if (depth == 0 && count[0] >= 120) report.append("truncatedAt=120 nodes\n");
    }
}
