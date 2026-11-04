package io.github.geminianchor;

import android.content.Context;
import android.util.AtomicFile;
import io.github.geminianchor.core.ConversationIndex.Entry;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Local app-private directories. The serial writer keeps old saves ahead of deletes. */
final class SessionStore {
    record Session(String id, String title, List<Entry> entries, long updatedAt) {
        Session { entries = List.copyOf(entries); }
    }
    private static final ExecutorService DISK = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "conversation-memory"); t.setDaemon(true); return t;
    });
    private final AtomicFile file;
    private final List<Session> sessions = new ArrayList<>();
    private String activeId;
    private boolean loadFailed;

    SessionStore(Context context, boolean restore) {
        // noBackupFilesDir also excludes device-to-device backup of conversation text.
        file = new AtomicFile(new File(context.getNoBackupFilesDir(), "conversation-memory.json"));
        if (restore) {
            try { DISK.submit(this::read).get(); }
            catch (Exception failure) { loadFailed = true; }
        }
        if (sessions.isEmpty()) create("");
    }
    List<Session> sessions() { return List.copyOf(sessions); }
    Session active() { return sessions.stream().filter(s -> s.id().equals(activeId)).findFirst().orElseThrow(); }
    boolean loadFailed() { return loadFailed; }
    Session create(String name) {
        Session session = new Session(UUID.randomUUID().toString(), cleanTitle(name), List.of(), System.currentTimeMillis());
        sessions.add(session); activeId = session.id(); return session;
    }
    boolean select(String id) {
        if (sessions.stream().noneMatch(s -> s.id().equals(id))) return false;
        activeId = id; return true;
    }
    void update(List<Entry> entries) {
        Session old = active();
        String title = old.title();
        if (title.equals("未命名会话") && !entries.isEmpty()) title = cleanTitle(entries.get(0).text());
        replace(new Session(old.id(), title, entries, System.currentTimeMillis()));
    }
    void rename(String title) {
        Session old = active(); replace(new Session(old.id(), cleanTitle(title), old.entries(), System.currentTimeMillis()));
    }
    void deleteActive() {
        sessions.removeIf(s -> s.id().equals(activeId));
        if (sessions.isEmpty()) create(""); else activeId = sessions.get(sessions.size() - 1).id();
    }
    private void replace(Session session) {
        for (int i = 0; i < sessions.size(); i++) if (sessions.get(i).id().equals(session.id())) { sessions.set(i, session); return; }
    }
    private static String cleanTitle(String raw) {
        String name = io.github.geminianchor.core.ConversationIndex.normalize(raw);
        if (name.isEmpty()) return "未命名会话";
        return name.substring(0, name.offsetByCodePoints(0, Math.min(40, name.codePointCount(0, name.length()))));
    }
    Future<Boolean> save() {
        if (loadFailed) return java.util.concurrent.CompletableFuture.completedFuture(false);
        // Capture on the service thread; the worker never sees mutable lists.
        List<Session> snapshot = sessions(); String selected = activeId;
        return DISK.submit(() -> write(snapshot, selected));
    }
    Future<Boolean> deleteAll() {
        sessions.clear(); activeId = null; loadFailed = false; create("");
        return DISK.submit(() -> { file.delete(); return !file.getBaseFile().exists(); });
    }
    private void read() {
        if (!file.getBaseFile().exists() && !new File(file.getBaseFile() + ".bak").exists()) return;
        try {
            JSONObject root = new JSONObject(new String(file.readFully(), StandardCharsets.UTF_8));
            if (root.getInt("version") != 1) throw new IllegalArgumentException("Unknown format");
            List<Session> loaded = new ArrayList<>(); HashSet<String> ids = new HashSet<>();
            JSONArray records = root.getJSONArray("sessions");
            for (int i = 0; i < records.length(); i++) {
                JSONObject s = records.getJSONObject(i); String id = s.getString("id");
                if (!ids.add(id)) throw new IllegalArgumentException("Duplicate session");
                List<Entry> entries = new ArrayList<>(); HashSet<Long> entryIds = new HashSet<>();
                JSONArray list = s.getJSONArray("entries");
                for (int j = 0; j < list.length(); j++) {
                    JSONObject e = list.getJSONObject(j); long entryId = e.getLong("id");
                    if (entryId < 0 || entryId == Long.MAX_VALUE || !entryIds.add(entryId)) throw new IllegalArgumentException("Invalid anchor");
                    entries.add(new Entry(entryId, e.getString("text"), e.getInt("row")));
                }
                loaded.add(new Session(id, s.getString("title"), entries, s.getLong("updatedAt")));
            }
            String selected = root.getString("activeId");
            if (!ids.contains(selected)) throw new IllegalArgumentException("Missing active session");
            sessions.addAll(loaded); activeId = selected;
        } catch (Exception failure) {
            // Never overwrite unreadable saved conversations with an empty file.
            loadFailed = true;
        }
    }
    private boolean write(List<Session> snapshot, String selected) {
        FileOutputStream stream = null;
        try {
            JSONArray records = new JSONArray();
            for (Session s : snapshot) {
                JSONArray entries = new JSONArray();
                for (Entry e : s.entries()) entries.put(new JSONObject().put("id", e.id()).put("text", e.text()).put("row", e.row()));
                records.put(new JSONObject().put("id", s.id()).put("title", s.title()).put("updatedAt", s.updatedAt()).put("entries", entries));
            }
            byte[] bytes = new JSONObject().put("version", 1).put("activeId", selected).put("sessions", records)
                    .toString().getBytes(StandardCharsets.UTF_8);
            stream = file.startWrite(); stream.write(bytes); file.finishWrite(stream); stream = null;
            // AtomicFile can log a failed final rename without throwing.
            return java.util.Arrays.equals(bytes, file.readFully());
        } catch (Exception failure) {
            if (stream != null) file.failWrite(stream);
            return false;
        }
    }
}
