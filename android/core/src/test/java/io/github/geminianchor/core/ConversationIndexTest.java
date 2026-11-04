package io.github.geminianchor.core;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class ConversationIndexTest {
    private List<String> texts(ConversationIndex index) {
        return index.entries().stream().map(ConversationIndex.Entry::text).collect(java.util.stream.Collectors.toList());
    }
    @Test public void upwardScanPrependsOverlappingEarlierViewports() {
        ConversationIndex index = new ConversationIndex();
        index.merge(List.of("第三段", "第四段", "第五段"), -1);
        long anchor = index.entries().get(0).id();
        index.merge(List.of("第一段", "第二段", "第三段"), -1);
        assertEquals(List.of("第一段", "第二段", "第三段", "第四段", "第五段"), texts(index));
        assertEquals(anchor, index.entries().get(2).id());
        index.merge(List.of("第二段", "第三段"), -1);
        assertEquals(5, index.entries().size());
    }
    @Test public void repeatedQuestionsRetainSeparateAnchors() {
        ConversationIndex index = new ConversationIndex();
        index.merge(List.of("继续", "回答甲", "继续", "回答乙"), 1);
        assertEquals(4, index.entries().size());
        assertNotEquals(index.entries().get(0).id(), index.entries().get(2).id());
        index.merge(List.of("继续", "回答乙", "最后"), 1);
        assertEquals(List.of("继续", "回答甲", "继续", "回答乙", "最后"), texts(index));
    }
    @Test public void detachedPagesKeepScanDirectionAndSearchNormalizes() {
        ConversationIndex index = new ConversationIndex();
        index.merge(List.of("  Hello\n WORLD ", "尾部"), -1);
        index.merge(List.of("开头"), -1);
        index.merge(List.of("追加"), 1);
        assertEquals(List.of("开头", "Hello WORLD", "尾部", "追加"), texts(index));
        assertEquals("Hello WORLD", index.search("hello world").get(0).text());
        assertTrue(index.search("不存在").isEmpty());
        index.clear(); assertTrue(index.entries().isEmpty());
    }
    @Test public void emptyPagesAndBlankNodesNeverInventEntries() {
        ConversationIndex index = new ConversationIndex();
        index.merge(List.of("", " \n "), 1);
        assertTrue(index.entries().isEmpty());
        index.merge(List.of("一段"), 1); index.merge(List.of(), -1);
        assertEquals(List.of("一段"), texts(index));
    }

    @Test public void rowAnchorsSortAndPreserveSeparateRepeatedPrompts() {
        ConversationIndex index = new ConversationIndex();
        index.putAtRow(8, "继续"); index.putAtRow(2, "继续"); index.putAtRow(4, "中间");
        assertEquals(List.of("继续", "中间", "继续"), texts(index));
        assertNotEquals(index.entries().get(0).id(), index.entries().get(2).id());
        long id = index.entries().get(0).id(); index.putAtRow(2, "继续");
        assertEquals(3, index.entries().size()); assertEquals(id, index.entries().get(0).id());
        index.putAtRow(2, "更新后内容"); assertNotEquals(id, index.entries().get(0).id());
        assertEquals(3, index.entries().size());
    }

    @Test public void restoringDirectoryRetainsIdsAndAllocatesNewIdsAfterExistingOnes() {
        ConversationIndex index = new ConversationIndex();
        index.restore(List.of(new ConversationIndex.Entry(40, "继续", 2), new ConversationIndex.Entry(41, "继续", 8)));
        index.putAtRow(0, "更早的问题");
        assertEquals(42, index.entries().get(0).id());
        assertEquals(40, index.entries().get(1).id());
        assertEquals(41, index.entries().get(2).id());
    }
}
