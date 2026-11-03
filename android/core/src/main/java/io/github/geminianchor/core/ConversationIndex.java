package io.github.geminianchor.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Ordered, memory-only index. Overlap merges preserve repeated identical turns. */
public final class ConversationIndex {
    public record Entry(long id, String text) {}
    private final List<Entry> entries = new ArrayList<>();
    private long nextId;
    public static String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }
    public List<Entry> entries() { return Collections.unmodifiableList(entries); }
    public void clear() { entries.clear(); }
    public List<Entry> search(String query) {
        String needle = normalize(query).toLowerCase(Locale.ROOT);
        return entries.stream().filter(e -> e.text().toLowerCase(Locale.ROOT).contains(needle)).collect(java.util.stream.Collectors.toList());
    }
    /** Merge contiguous viewport text; direction is -1 for earlier, +1 for later. */
    public void merge(List<String> raw, int direction) {
        List<String> page = raw.stream().map(ConversationIndex::normalize).filter(s -> !s.isEmpty()).collect(java.util.stream.Collectors.toList());
        if (page.isEmpty()) return;
        if (entries.isEmpty()) { page.forEach(s -> entries.add(new Entry(nextId++, s))); return; }
        // Find the longest overlap anywhere, not only at the edges. An unchanged
        // or partially visible middle viewport must not append duplicate turns.
        int best = 0, at = -1, pageAt = -1;
        for (int i = 0; i < entries.size(); i++) {
            for (int j = 0; j < page.size(); j++) {
                int count = 0;
                while (i + count < entries.size() && j + count < page.size()
                        && entries.get(i + count).text().equals(page.get(j + count))) count++;
                if (count > best || (count == best && count > 0 && direction > 0)) {
                    best = count; at = i; pageAt = j;
                }
            }
        }
        if (best == 0) {
            List<Entry> additions = page.stream().map(s -> new Entry(nextId++, s)).collect(java.util.stream.Collectors.toList());
            entries.addAll(direction < 0 ? 0 : entries.size(), additions);
        } else {
            if (at == 0 && pageAt > 0) {
                List<Entry> prefix = page.subList(0, pageAt).stream().map(s -> new Entry(nextId++, s)).collect(java.util.stream.Collectors.toList());
                entries.addAll(0, prefix); at += prefix.size();
            }
            if (at + best == entries.size() && pageAt + best < page.size()) {
                page.subList(pageAt + best, page.size()).forEach(s -> entries.add(new Entry(nextId++, s)));
            }
        }
    }
}
