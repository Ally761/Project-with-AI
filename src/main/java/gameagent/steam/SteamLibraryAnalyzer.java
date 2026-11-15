package gameagent.steam;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import gameagent.data.DataSourceException;
import gameagent.data.GameDataSource;
import gameagent.model.Game;
import gameagent.model.OwnedGame;
import gameagent.profile.Category;
import gameagent.profile.ProfileUpdate;
import gameagent.profile.UserProfile;

/**
 * Turns a Steam library into profile changes. Playtime is the signal: what someone plays for hours, they like.
 * <ul>
 *   <li>The most played games (and a few played in the last two weeks) become liked games.</li>
 *   <li>Genres shared by those games, weighted by hours, become liked genres.</li>
 *   <li>Never overrides the user: anything they already have an opinion on is left alone.</li>
 *   <li>Unplayed or barely played games say nothing about taste, so they only count as "owned".</li>
 * </ul>
 */
public class SteamLibraryAnalyzer {
    static final int TOP_GAMES = 10;
    static final int RECENT_GAMES = 3;
    static final int GENRE_SAMPLE = 8;
    static final int MAX_GENRES = 4;
    static final double MIN_HOURS = 2.0;
    static final double MIN_RECENT_HOURS = 1.0;

    /** Steam store labels that describe software or content warnings rather than taste. */
    private static final Set<String> IGNORED_GENRES = Set.of("free to play", "early access", "utilities", "design & illustration",
            "animation & modeling", "audio production", "video production", "photo editing", "web publishing", "game development",
            "software training", "education", "accounting", "movie", "documentary", "episodic", "short", "tutorial",
            "gore", "nudity", "sexual content", "violent");

    /** Library entries that are tools, not games anyone chose to play. */
    private static final Pattern NOT_A_GAME = Pattern.compile(
            "(?i)(dedicated server|\\bsdk\\b|soundtrack|\\bproton\\b|steam linux runtime|wallpaper engine|steamvr|benchmark|playtest|\\bdemo\\b|authoring tools|workshop tools)");

    /** True for library entries that are tools or extras (servers, SDKs, soundtracks, wallpaper apps) rather than games. */
    public static boolean isTool(String name) {
        return name != null && NOT_A_GAME.matcher(name).find();
    }

    /** What importing this library would change, plus anything worth telling the user. */
    public record Plan(ProfileUpdate update, List<OwnedGame> counted, List<String> warnings) {}

    private final GameDataSource data;

    public SteamLibraryAnalyzer(GameDataSource data) {
        this.data = data;
    }

    public Plan plan(SteamLibrary lib, UserProfile profile) {
        ProfileUpdate update = new ProfileUpdate("steam");
        List<String> warnings = new ArrayList<>();

        List<OwnedGame> played = lib.games().stream()
                .filter(g -> !isTool(g.name()))
                .toList();
        boolean playtimeHidden = !played.isEmpty() && played.stream().noneMatch(g -> g.hours() > 0);
        if (playtimeHidden) {
            warnings.add("Playtime on that profile is hidden, so I can't tell which games you play most. "
                    + "Your library is saved (owned games won't be recommended) but no tastes were imported. "
                    + "Untick \"Always keep my total playtime private\" in Steam's privacy settings and run /steam refresh.");
            return new Plan(update, List.of(), warnings);
        }

        // Most played overall, then a few recent favourites that haven't made the cut yet.
        List<OwnedGame> picks = new ArrayList<>(played.stream()
                .filter(g -> g.hours() >= MIN_HOURS)
                .sorted(Comparator.comparingDouble(OwnedGame::hours).reversed())
                .limit(TOP_GAMES).toList());
        played.stream()
                .filter(g -> g.hoursRecent() >= MIN_RECENT_HOURS && g.hours() >= MIN_HOURS && !picks.contains(g))
                .sorted(Comparator.comparingDouble(OwnedGame::hoursRecent).reversed())
                .limit(RECENT_GAMES)
                .forEach(picks::add);

        for (OwnedGame g : picks) {
            if (profile.find(Category.GAME, g.name()).isEmpty()) update.like(Category.GAME, g.name());
        }
        addGenres(picks, profile, update, warnings);
        return new Plan(update, picks, warnings);
    }

    private void addGenres(List<OwnedGame> picks, UserProfile profile, ProfileUpdate update, List<String> warnings) {
        Map<String, Double> hoursByGenre = new LinkedHashMap<>();
        Map<String, Integer> gamesByGenre = new LinkedHashMap<>();
        int looked = 0, failures = 0;
        List<OwnedGame> sample = picks.stream()
                .sorted(Comparator.comparingDouble(OwnedGame::hours).reversed())
                .limit(GENRE_SAMPLE).toList();
        for (OwnedGame g : sample) {
            if (failures >= 2) break; // the store is down: stop hammering it
            try {
                Optional<Game> details = data.findByAppId(g.appId(), g.name());
                if (details.isEmpty()) continue;
                looked++;
                Set<String> seen = new HashSet<>();
                for (String genre : details.get().genres()) {
                    if (IGNORED_GENRES.contains(genre.toLowerCase(Locale.ROOT)) || !seen.add(genre)) continue;
                    hoursByGenre.merge(genre, g.hours(), Double::sum);
                    gamesByGenre.merge(genre, 1, Integer::sum);
                }
            } catch (DataSourceException e) {
                failures++;
            }
        }
        if (failures >= 2) {
            warnings.add("The Steam store was unreachable, so I skipped working out your favourite genres. Run /steam refresh later to add them.");
        }
        if (hoursByGenre.isEmpty()) return;

        // Genres that recur across several of your games say more than a one-off.
        List<String> ranked = hoursByGenre.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(Map.Entry::getKey).toList();
        List<String> recurring = ranked.stream().filter(gn -> gamesByGenre.get(gn) >= 2).limit(MAX_GENRES).toList();
        List<String> chosen = recurring.isEmpty() ? ranked.stream().limit(2).toList() : recurring;
        for (String genre : chosen) {
            if (profile.find(Category.GENRE, genre).isEmpty()) update.like(Category.GENRE, genre);
        }
    }
}
