package io.github.geminianchor;

import android.content.Context;
import io.github.geminianchor.core.ConversationIndex.Entry;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 35})
public class SessionStoreTest {
    private Context context;
    @Before public void setup() { context = RuntimeEnvironment.getApplication(); }
    private File path() { return new File(context.getNoBackupFilesDir(), "conversation-memory.json"); }
    @Test public void roundTripsSeparateSessionsRowsRepeatedPromptsAndStableIds() throws Exception {
        SessionStore store = new SessionStore(context, true);
        String first = store.active().id(); store.rename("甲");
        List<Entry> repeated = List.of(new Entry(8, "继续", 4), new Entry(22, "继续", 10));
        store.update(repeated); store.create("乙"); store.update(List.of(new Entry(0, "第二个会话", -1)));
        assertTrue(store.save().get(5, TimeUnit.SECONDS));
        SessionStore restored = new SessionStore(context, true);
        assertEquals(2, restored.sessions().size()); assertEquals("乙", restored.active().title());
        assertTrue(restored.select(first)); assertEquals(repeated, restored.active().entries());
        restored.deleteActive(); assertTrue(restored.save().get(5, TimeUnit.SECONDS));
        assertEquals(1, new SessionStore(context, true).sessions().size());
    }
    @Test public void queuedSaveCapturesItsOwnImmutableSnapshot() throws Exception {
        SessionStore store = new SessionStore(context, false);
        java.util.ArrayList<Entry> data = new java.util.ArrayList<>(List.of(new Entry(0, "保存版本", 0)));
        store.update(data); java.util.concurrent.Future<Boolean> saved = store.save();
        data.clear(); store.update(List.of(new Entry(1, "内存新版本", 2)));
        assertTrue(saved.get(5, TimeUnit.SECONDS));
        assertEquals("保存版本", new SessionStore(context, true).active().entries().get(0).text());
    }
    @Test public void corruptFileIsNotOverwrittenByAnEmptyDirectory() throws Exception {
        Files.write(path().toPath(), "broken archive".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        SessionStore store = new SessionStore(context, true);
        assertTrue(store.loadFailed()); store.create("新目录");
        assertFalse(store.save().get(5, TimeUnit.SECONDS));
        assertEquals("broken archive", new String(Files.readAllBytes(path().toPath()), java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(store.deleteAll().get(5, TimeUnit.SECONDS)); assertFalse(path().exists());
        assertTrue(store.save().get(5, TimeUnit.SECONDS));
    }
    @Test public void interruptedAtomicWriteRecoversPreviousCompleteDirectory() throws Exception {
        SessionStore store = new SessionStore(context, false); store.update(List.of(new Entry(7, "已保存", 8)));
        assertTrue(store.save().get(5, TimeUnit.SECONDS));
        // AtomicFile recovers a pre-API-30 backup before attempting JSON parsing.
        Files.move(path().toPath(), new File(path() + ".bak").toPath());
        Files.write(path().toPath(), "partial write".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        SessionStore restored = new SessionStore(context, true);
        assertFalse(restored.loadFailed()); assertEquals("已保存", restored.active().entries().get(0).text());
    }
    @Test public void deletingAllRunsAfterPendingWritesAndKeepsNoConversationFile() throws Exception {
        SessionStore store = new SessionStore(context, false);
        store.update(List.of(new Entry(0, "应删除的私密文字", 0))); store.save();
        assertTrue(store.deleteAll().get(5, TimeUnit.SECONDS));
        assertFalse(path().exists()); assertTrue(new SessionStore(context, true).active().entries().isEmpty());
    }
    @Test public void storageFailureIsReportedInsteadOfClaimingSuccess() throws Exception {
        File blocker = new File(context.getNoBackupFilesDir(), "not-a-directory");
        Files.write(blocker.toPath(), new byte[]{1});
        SessionStore store = new SessionStore(context, false);
        org.robolectric.util.ReflectionHelpers.setField(store, "file", new android.util.AtomicFile(new File(blocker, "data.json")));
        store.update(List.of(new Entry(0, "未保存", 0)));
        assertFalse(store.save().get(5, TimeUnit.SECONDS));
    }
}
