package gameagent.recommend;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import gameagent.model.Game;
import gameagent.model.Recommendation;
import gameagent.profile.ProfileEvent;
import gameagent.profile.ProfileListener;
import gameagent.util.Json;
import gameagent.util.Names;

/**
 * The user's personalised catalogue: recommendations accumulated over time and kept between sessions.
 * Observes the profile and flags itself "stale" when preferences change so the UI can suggest a refresh.
 */
public class GameCatalog implements ProfileListener {
    private static final int MAX = 100;

    private final Path file;
    private final List<Recommendation> entries = new ArrayList<>();
    private boolean stale = false;

    public GameCatalog(Path file) {
        this.file = file;
        load();
    }

    @Override
    public synchronized void onProfileChanged(ProfileEvent event) {
        if (event.type().affectsRecommendations()) stale = true;
    }

    public synchronized boolean isStale() { return stale; }

    public synchronized List<Recommendation> all() {
        return Collections.unmodifiableList(new ArrayList<>(entries));
    }

    /** Adds new picks at the top, replacing older entries for the same game. */
    public synchronized void addAll(List<Recommendation> recs, boolean freshForCurrentProfile) {
        for (int i = recs.size() - 1; i >= 0; i--) {
            Recommendation r = recs.get(i);
            entries.removeIf(e -> Names.norm(e.game().name()).equals(Names.norm(r.game().name())));
            entries.add(0, r);
        }
        while (entries.size() > MAX) entries.remove(entries.size() - 1);
        if (freshForCurrentProfile) stale = false;
        save();
    }

    public synchronized void clear() {
        entries.clear();
        save();
    }

    private void save() {
        if (file == null) return;
        try {
            List<Object> list = new ArrayList<>();
            for (Recommendation r : entries) {
                Map<String, Object> m = new LinkedHashMap<>();
                Game g = r.game();
                m.put("name", g.name());
                m.put("steamAppId", g.steamAppId());
                m.put("description", g.description());
                m.put("genres", g.genres());
                m.put("price", g.price());
                m.put("releaseDate", g.releaseDate());
                m.put("url", g.url());
                m.put("verified", g.verified());
                m.put("reason", r.reason());
                m.put("score", r.matchScore());
                m.put("label", r.label() == null ? "" : r.label());
                list.add(m);
            }
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("saved", Instant.now().toString());
            root.put("entries", list);
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            Files.writeString(file, Json.pretty(root), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("Warning: could not save catalog: " + e.getMessage());
        }
    }

    private void load() {
        if (file == null || !Files.isRegularFile(file)) return;
        try {
            Map<String, Object> root = Json.parseObject(Files.readString(file, StandardCharsets.UTF_8));
            for (Object o : Json.list(root, "entries")) {
                Map<String, Object> m = Json.asObject(o);
                String name = Json.str(m, "name");
                if (name == null) continue;
                Game g = new Game(name, (long) Json.num(m, "steamAppId", 0), Json.str(m, "description", ""),
                        Json.strList(m, "genres"), Json.str(m, "price", ""), Json.str(m, "releaseDate", ""),
                        Json.str(m, "url", ""), Json.bool(m, "verified", false));
                String label = Json.str(m, "label", "");
                entries.add(new Recommendation(g, Json.str(m, "reason", ""), Json.integer(m, "score", 0), label.isEmpty() ? null : label));
            }
        } catch (Exception e) {
            System.err.println("Warning: could not read catalog (" + e.getMessage() + "); starting empty.");
            entries.clear();
        }
    }
}
