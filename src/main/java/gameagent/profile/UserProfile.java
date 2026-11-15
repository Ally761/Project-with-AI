package gameagent.profile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import gameagent.model.GameFeedback;
import gameagent.model.LogEntry;
import gameagent.model.OwnedGame;
import gameagent.model.SteamLink;
import gameagent.util.Names;

/**
 * The agent's long-term memory of the user: weighted likes/dislikes, free-text notes, game feedback,
 * an interaction log and the games already recommended. Read access is public; changes go through
 * {@link ProfileManager} so observers get notified.
 */
public class UserProfile {
    private final Map<String, Preference> prefs = new LinkedHashMap<>();
    private final List<String> notes = new ArrayList<>();
    private final List<GameFeedback> feedback = new ArrayList<>();
    private final List<LogEntry> log = new ArrayList<>();
    private final Set<String> shown = new LinkedHashSet<>();
    private SteamLink steamLink;
    private final List<OwnedGame> owned = new ArrayList<>();
    private final Set<String> ownedKeys = new java.util.HashSet<>();

    static String key(Category c, String name) {
        return c + ":" + normalize(name);
    }

    public static String normalize(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------ read access

    public List<Preference> preferences() {
        return Collections.unmodifiableList(new ArrayList<>(prefs.values()));
    }

    public List<Preference> preferences(Category c, Sentiment s) {
        return prefs.values().stream()
                .filter(p -> p.category() == c && p.sentiment() == s)
                .sorted((a, b) -> Double.compare(b.weight(), a.weight()))
                .collect(Collectors.toList());
    }

    public Optional<Preference> find(Category c, String name) {
        return Optional.ofNullable(prefs.get(key(c, name)));
    }

    /** True if the user already expressed any opinion about this game (liked or disliked). */
    public boolean hasOpinionOnGame(String gameName) {
        return find(Category.GAME, gameName).isPresent();
    }

    public List<String> notes() { return Collections.unmodifiableList(notes); }
    public List<GameFeedback> feedback() { return Collections.unmodifiableList(feedback); }
    public List<LogEntry> log() { return Collections.unmodifiableList(log); }
    public Set<String> shownGames() { return Collections.unmodifiableSet(shown); }

    /** The linked public Steam profile, if any. */
    public Optional<SteamLink> steamLink() { return Optional.ofNullable(steamLink); }

    /** Games in the linked Steam library (empty when no profile is linked). */
    public List<OwnedGame> ownedGames() { return Collections.unmodifiableList(owned); }

    /** True if the title is in the linked Steam library (compared loosely: case, punctuation and trademark signs ignored). */
    public boolean owns(String gameName) {
        return !ownedKeys.isEmpty() && ownedKeys.contains(Names.norm(gameName));
    }

    /** Owned games, most played first. */
    public List<OwnedGame> mostPlayed(int limit) {
        return owned.stream()
                .sorted((a, b) -> Double.compare(b.hours(), a.hours()))
                .limit(limit)
                .collect(Collectors.toList());
    }

    public boolean isEmpty() {
        return prefs.isEmpty() && notes.isEmpty() && feedback.isEmpty() && steamLink == null;
    }

    /** Too little information to personalise well. */
    public boolean isSparse() {
        long positives = prefs.values().stream().filter(p -> p.sentiment() == Sentiment.LIKE).count();
        return positives < 3;
    }

    /** Deterministic gap analysis used to steer the refinement module. */
    public List<String> gaps() {
        List<String> g = new ArrayList<>();
        if (preferences(Category.GENRE, Sentiment.LIKE).isEmpty()) g.add("favourite genres are unknown");
        if (preferences(Category.GAME, Sentiment.LIKE).size() < 3) g.add("fewer than 3 favourite games are known");
        boolean anyDislike = prefs.values().stream().anyMatch(p -> p.sentiment() == Sentiment.DISLIKE);
        if (!anyDislike) g.add("nothing the user dislikes or wants to avoid is known");
        if (preferences(Category.PLATFORM, Sentiment.LIKE).isEmpty()) g.add("platforms are unknown");
        if (notes.isEmpty()) g.add("play-session length, budget, difficulty and multiplayer preferences are unknown");
        return g;
    }

    /** Compact text description for prompts. */
    public String toPromptSummary() {
        StringBuilder sb = new StringBuilder();
        line(sb, "Liked genres", preferences(Category.GENRE, Sentiment.LIKE));
        line(sb, "Disliked genres", preferences(Category.GENRE, Sentiment.DISLIKE));
        line(sb, "Liked themes/tags", preferences(Category.TAG, Sentiment.LIKE));
        line(sb, "Disliked themes/tags", preferences(Category.TAG, Sentiment.DISLIKE));
        line(sb, "Liked games", preferences(Category.GAME, Sentiment.LIKE));
        line(sb, "Disliked games", preferences(Category.GAME, Sentiment.DISLIKE));
        line(sb, "Platforms", preferences(Category.PLATFORM, Sentiment.LIKE));
        if (!notes.isEmpty()) sb.append("Notes/constraints: ").append(String.join("; ", notes)).append('\n');
        if (steamLink != null && !owned.isEmpty()) {
            sb.append("Steam profile (linked), most played: ").append(mostPlayed(8).stream().filter(g -> g.hours() >= 1)
                            .map(g -> g.name() + " (" + Math.round(g.hours()) + " h)").collect(Collectors.joining(", ")))
                    .append(". The user already owns these, so do not recommend them.\n");
        }
        if (!feedback.isEmpty()) {
            sb.append("Game feedback: ");
            sb.append(feedback.stream().skip(Math.max(0, feedback.size() - 8))
                    .map(f -> f.gameName() + " " + f.rating() + "/5" + (f.comment().isBlank() ? "" : " (\"" + f.comment() + "\")"))
                    .collect(Collectors.joining("; ")));
            sb.append('\n');
        }
        if (sb.length() == 0) return "(nothing known about the user yet)";
        return sb.toString().trim();
    }

    private static void line(StringBuilder sb, String label, List<Preference> items) {
        if (items.isEmpty()) return;
        sb.append(label).append(": ")
                .append(items.stream().map(Preference::toString).collect(Collectors.joining(", ")))
                .append('\n');
    }

    // ------------------------------------------------------------ package-private mutation (ProfileManager only)

    Map<String, Preference> prefMap() { return prefs; }
    List<String> noteList() { return notes; }
    List<GameFeedback> feedbackList() { return feedback; }
    List<LogEntry> logList() { return log; }
    Set<String> shownSet() { return shown; }

    void setSteam(SteamLink link, List<OwnedGame> games) {
        this.steamLink = link;
        owned.clear();
        ownedKeys.clear();
        if (games != null) {
            owned.addAll(games);
            for (OwnedGame g : games) ownedKeys.add(Names.norm(g.name()));
        }
    }

    void clearSteam() {
        setSteam(null, List.of());
    }
}
