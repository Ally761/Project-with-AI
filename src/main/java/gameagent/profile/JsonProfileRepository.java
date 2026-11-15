package gameagent.profile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import gameagent.model.GameFeedback;
import gameagent.model.LogEntry;
import gameagent.util.Json;
import gameagent.util.JsonException;

/** Stores the profile as pretty JSON in the data directory (git-ignored). */
public class JsonProfileRepository implements ProfileRepository {
    private final Path file;

    public JsonProfileRepository(Path file) {
        this.file = file;
    }

    @Override
    public UserProfile load() {
        UserProfile p = new UserProfile();
        if (!Files.isRegularFile(file)) return p;
        try {
            Map<String, Object> root = Json.parseObject(Files.readString(file, StandardCharsets.UTF_8));
            for (Object o : Json.list(root, "preferences")) {
                Map<String, Object> m = Json.asObject(o);
                String name = Json.str(m, "name");
                if (name == null) continue;
                Category c = Category.parse(Json.str(m, "category"));
                Sentiment s = "DISLIKE".equalsIgnoreCase(Json.str(m, "sentiment")) ? Sentiment.DISLIKE : Sentiment.LIKE;
                Preference pref = new Preference(c, name, s, Json.num(m, "weight", 3), Json.str(m, "source", "user"),
                        instant(Json.str(m, "updated")));
                p.prefMap().put(UserProfile.key(c, name), pref);
            }
            p.noteList().addAll(Json.strList(root, "notes"));
            for (Object o : Json.list(root, "feedback")) {
                Map<String, Object> m = Json.asObject(o);
                p.feedbackList().add(new GameFeedback(Json.str(m, "game", "?"), Json.integer(m, "rating", 3),
                        Json.str(m, "comment", ""), instant(Json.str(m, "when"))));
            }
            for (Object o : Json.list(root, "log")) {
                Map<String, Object> m = Json.asObject(o);
                p.logList().add(new LogEntry(instant(Json.str(m, "time")), Json.str(m, "type", ""), Json.str(m, "text", "")));
            }
            p.shownSet().addAll(Json.strList(root, "shown"));
        } catch (IOException | JsonException e) {
            System.err.println("Warning: could not read profile (" + e.getMessage() + "); starting empty. File kept at " + file);
            return new UserProfile();
        }
        return p;
    }

    @Override
    public synchronized void save(UserProfile p) throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        List<Object> prefs = new ArrayList<>();
        for (Preference pr : p.preferences()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("category", pr.category().name());
            m.put("name", pr.name());
            m.put("sentiment", pr.sentiment().name());
            m.put("weight", pr.weight());
            m.put("source", pr.source());
            m.put("updated", pr.updated().toString());
            prefs.add(m);
        }
        root.put("preferences", prefs);
        root.put("notes", p.notes());
        List<Object> fb = new ArrayList<>();
        for (GameFeedback f : p.feedback()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("game", f.gameName());
            m.put("rating", f.rating());
            m.put("comment", f.comment());
            m.put("when", f.when().toString());
            fb.add(m);
        }
        root.put("feedback", fb);
        List<Object> log = new ArrayList<>();
        for (LogEntry e : p.log()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("time", e.time().toString());
            m.put("type", e.type());
            m.put("text", e.text());
            log.add(m);
        }
        root.put("log", log);
        root.put("shown", new ArrayList<>(p.shownGames()));

        if (file.getParent() != null) Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, Json.pretty(root), StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    private static Instant instant(String s) {
        try {
            return s == null ? Instant.now() : Instant.parse(s);
        } catch (Exception e) {
            return Instant.now();
        }
    }
}
