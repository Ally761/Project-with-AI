package gameagent.profile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import gameagent.util.Json;

/**
 * A batch of requested profile changes. Built either programmatically (CLI commands) or from the JSON
 * an LLM produced ({@link #fromJson}). Parsing is lenient: malformed entries are skipped, never fatal.
 */
public final class ProfileUpdate {
    public enum Op { ADD, REMOVE }

    public record Change(Op op, Category category, String name, Sentiment sentiment) {}

    private final List<Change> changes = new ArrayList<>();
    private final List<String> addNotes = new ArrayList<>();
    private final String source;

    public ProfileUpdate(String source) {
        this.source = source;
    }

    public String source() { return source; }
    public List<Change> changes() { return changes; }
    public List<String> addNotes() { return addNotes; }
    public boolean isEmpty() { return changes.isEmpty() && addNotes.isEmpty(); }

    public ProfileUpdate like(Category c, String name) {
        if (name != null && !name.isBlank()) changes.add(new Change(Op.ADD, c, name.trim(), Sentiment.LIKE));
        return this;
    }

    public ProfileUpdate dislike(Category c, String name) {
        if (name != null && !name.isBlank()) changes.add(new Change(Op.ADD, c, name.trim(), Sentiment.DISLIKE));
        return this;
    }

    public ProfileUpdate remove(Category c, String name) {
        if (name != null && !name.isBlank()) changes.add(new Change(Op.REMOVE, c, name.trim(), null));
        return this;
    }

    public ProfileUpdate note(String note) {
        if (note != null && !note.isBlank()) addNotes.add(note.trim());
        return this;
    }

    /**
     * Expected shape:
     * {"likes":[{"category":"genre","name":"Roguelike"}], "dislikes":[...], "remove":[...], "notes":["short sessions"]}
     * Entries may also be bare strings (treated as TAG).
     */
    public static ProfileUpdate fromJson(Map<String, Object> json, String source) {
        ProfileUpdate u = new ProfileUpdate(source);
        if (json == null) return u;
        readEntries(json, "likes", u, Op.ADD, Sentiment.LIKE);
        readEntries(json, "dislikes", u, Op.ADD, Sentiment.DISLIKE);
        readEntries(json, "remove", u, Op.REMOVE, null);
        for (String n : Json.strList(json, "notes")) u.note(n);
        return u;
    }

    private static void readEntries(Map<String, Object> json, String key, ProfileUpdate u, Op op, Sentiment s) {
        for (Object o : Json.list(json, key)) {
            Category cat = Category.TAG;
            String name = null;
            if (o instanceof String str) {
                name = str;
            } else if (o instanceof Map<?, ?>) {
                Map<String, Object> m = Json.asObject(o);
                name = Json.str(m, "name");
                cat = Category.parse(Json.str(m, "category"));
            }
            if (name == null || name.isBlank() || name.length() > 80) continue;
            u.changes.add(new Change(op, cat, name.trim(), s));
        }
    }
}
