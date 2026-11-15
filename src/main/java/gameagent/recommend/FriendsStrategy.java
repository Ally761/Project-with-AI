package gameagent.recommend;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import gameagent.data.GameDataSource;
import gameagent.friends.Friend;
import gameagent.friends.FriendRoster;
import gameagent.llm.LLMClient;
import gameagent.llm.LLMException;
import gameagent.llm.LLMRequest;
import gameagent.llm.PromptBuilder;
import gameagent.model.Game;
import gameagent.model.GameFeedback;
import gameagent.model.OwnedGame;
import gameagent.model.Recommendation;
import gameagent.profile.Category;
import gameagent.profile.Preference;
import gameagent.profile.ProfileManager;
import gameagent.profile.Sentiment;
import gameagent.profile.UserProfile;
import gameagent.steam.SteamLibraryAnalyzer;
import gameagent.util.Json;
import gameagent.util.JsonException;
import gameagent.util.Names;

/**
 * Recommends what the user's friends play. Deterministic part: collect friends' games, drop what the user owns or has an
 * opinion on, score by how much and how many friends play it, fetch genres, and compare them with the user's weighted
 * tastes. AI part: pick the best few from that shortlist and explain each in terms of the user's profile and the friends.
 */
public class FriendsStrategy implements RecommendationStrategy {
    static final int SHORTLIST = 12;

    private final LLMClient llm;
    private final GameDataSource data;
    private final ProfileManager profiles;
    private final FriendRoster roster;

    public FriendsStrategy(LLMClient llm, GameDataSource data, ProfileManager profiles, FriendRoster roster) {
        this.llm = llm;
        this.data = data;
        this.profiles = profiles;
        this.roster = roster;
    }

    @Override
    public String name() { return "friends"; }

    /** One game as seen across friends, before the user's taste is applied. */
    static final class Candidate {
        final String name;
        long appId;
        final Map<String, Double> hoursByFriend = new LinkedHashMap<>();
        double popularity;
        Game game;
        double taste;
        double score;

        Candidate(String name) { this.name = name; }
    }

    @Override
    public RecommendationResult recommend(RecommendationRequest req) throws RecommendationException {
        List<Friend> friends = roster.friends();
        if (friends.isEmpty()) throw new RecommendationException("You haven't added any friends yet. Add a friend's public Steam profile link first.");
        if (!req.friend().isBlank()) {
            String q = req.friend().toLowerCase(Locale.ROOT);
            friends = friends.stream().filter(f -> f.name().toLowerCase(Locale.ROOT).startsWith(q) || f.steamId().equals(q)).toList();
            if (friends.isEmpty()) throw new RecommendationException("I don't have a friend called \"" + req.friend() + "\". Check your friends list.");
        }
        UserProfile profile = profiles.profile();
        List<String> warnings = new ArrayList<>();
        warnings.add("Steam only shows each friend's most-played games without signing in, so this is based on those.");

        List<Candidate> pool = candidates(friends, profile);
        if (pool.isEmpty()) {
            throw new RecommendationException(friends.size() == 1
                    ? friends.get(0).name() + " doesn't play anything new to you that I can see (you already own, rated or were shown their games)."
                    : "Your friends don't play anything new to you that I can see (you already own, rated or were shown their games).");
        }
        pool.sort((a, b) -> Double.compare(b.popularity, a.popularity));
        List<Candidate> shortlist = new ArrayList<>(pool.subList(0, Math.min(SHORTLIST, pool.size())));

        enrich(shortlist, warnings);
        for (Candidate c : shortlist) {
            c.taste = tasteScore(c.game, profile);
            c.score = Math.round(Math.max(0, Math.min(100, 50 * c.popularity / maxPopularity(shortlist) + 25 + c.taste * 6)));
        }
        shortlist.sort((a, b) -> Double.compare(b.score, a.score));

        List<Recommendation> result = rank(shortlist, profile, req, warnings);
        profiles.markShown(result.stream().map(r -> r.game().name()).toList());
        return new RecommendationResult(result, warnings);
    }

    private List<Candidate> candidates(List<Friend> friends, UserProfile profile) {
        Set<String> skip = new HashSet<>();
        for (Preference p : profile.preferences()) if (p.category() == Category.GAME) skip.add(Names.norm(p.name()));
        for (GameFeedback f : profile.feedback()) skip.add(Names.norm(f.gameName()));
        List<String> shown = new ArrayList<>(profile.shownGames());
        for (String s : shown.subList(Math.max(0, shown.size() - 30), shown.size())) skip.add(Names.norm(s));

        Map<String, Candidate> byKey = new LinkedHashMap<>();
        for (Friend f : friends) {
            for (OwnedGame g : f.games()) {
                String key = Names.norm(g.name());
                if (key.isEmpty() || g.hours() < 2 && g.hoursRecent() <= 0 || SteamLibraryAnalyzer.isTool(g.name())) continue;
                if (skip.contains(key) || profile.owns(g.name())) continue;
                Candidate c = byKey.computeIfAbsent(key, k -> new Candidate(g.name()));
                if (c.appId == 0) c.appId = g.appId();
                c.hoursByFriend.merge(f.name(), g.hours(), Double::sum);
            }
        }
        for (Candidate c : byKey.values()) {
            double sum = 0;
            for (Map.Entry<String, Double> e : c.hoursByFriend.entrySet()) {
                OwnedGame og = friends.stream().filter(f -> f.name().equals(e.getKey())).flatMap(f -> f.games().stream())
                        .filter(x -> Names.norm(x.name()).equals(Names.norm(c.name))).findFirst().orElse(null);
                sum += Math.log1p(e.getValue()) + (og == null ? 0 : 0.5 * Math.log1p(og.hoursRecent()));
            }
            c.popularity = sum * (1 + 0.5 * (c.hoursByFriend.size() - 1)); // several friends playing the same game counts for more
        }
        return new ArrayList<>(byKey.values());
    }

    private static double maxPopularity(List<Candidate> list) {
        double m = 0;
        for (Candidate c : list) m = Math.max(m, c.popularity);
        return m <= 0 ? 1 : m;
    }

    /** Fetches genres, price and a store link for the shortlist; unreachable data degrades to bare titles. */
    private void enrich(List<Candidate> list, List<String> warnings) {
        int failures = 0;
        for (Candidate c : list) {
            try {
                Optional<Game> g = c.appId > 0 ? data.findByAppId(c.appId, c.name) : data.findByName(c.name);
                c.game = g.orElse(null);
            } catch (Exception e) {
                failures++;
            }
            if (c.game == null) {
                c.game = new Game(c.name, c.appId, "", List.of(), "", "", c.appId > 0 ? "https://store.steampowered.com/app/" + c.appId : "", false);
            }
        }
        if (failures > 0) warnings.add("The game database was unreachable for " + failures + " game" + (failures == 1 ? "" : "s") + ", so genres and prices are missing for them.");
    }

    /** Sum of the user's weights for matching likes minus dislikes, over the game's genres. */
    public static double tasteScore(Game g, UserProfile profile) {
        double total = 0;
        for (String genre : g.genres()) {
            for (Preference p : profile.preferences()) {
                if (p.category() != Category.GENRE && p.category() != Category.TAG) continue;
                if (Names.norm(p.name()).equals(Names.norm(genre))) total += p.sentiment() == Sentiment.LIKE ? p.weight() : -p.weight();
            }
        }
        return total;
    }

    private List<Recommendation> rank(List<Candidate> shortlist, UserProfile profile, RecommendationRequest req, List<String> warnings)
            throws RecommendationException {
        Map<String, Candidate> byKey = new LinkedHashMap<>();
        StringBuilder block = new StringBuilder();
        for (Candidate c : shortlist) {
            byKey.put(Names.norm(c.name), c);
            block.append("- ").append(c.name).append(" | played by: ");
            List<String> who = new ArrayList<>();
            c.hoursByFriend.forEach((n, h) -> who.add(n + " (" + Math.round(h) + " h)"));
            block.append(String.join(", ", who));
            if (!c.game.genres().isEmpty()) block.append(" | genres: ").append(String.join(", ", c.game.genres()));
            if (!c.game.description().isBlank()) block.append(" | ").append(c.game.description().length() > 140 ? c.game.description().substring(0, 137) + "..." : c.game.description());
            block.append('\n');
        }
        List<Recommendation> out = new ArrayList<>();
        try {
            String raw = llm.complete(LLMRequest.json(PromptBuilder.friendRankSystem(),
                    PromptBuilder.friendRankUser(profile.toPromptSummary(), req.query(), block.toString(), req.count())));
            Set<String> used = new HashSet<>();
            for (Object r : Json.list(Json.parseLenientObject(raw), "recommendations")) {
                Map<String, Object> m = Json.asObject(r);
                if (m == null) continue;
                String key = Names.norm(Json.str(m, "name", ""));
                Candidate c = byKey.get(key);
                if (c == null || !used.add(key)) continue;
                int score = Math.max(0, Math.min(100, Json.integer(m, "score", (int) c.score)));
                out.add(new Recommendation(c.game, Json.str(m, "reason", fallbackReason(c)), score, label(c)));
                if (out.size() >= req.count()) break;
            }
        } catch (LLMException e) {
            warnings.add("The AI is unavailable, so these are ordered by how much your friends play them and how well their genres fit your tastes.");
        } catch (JsonException e) {
            warnings.add("The AI's ranking was unreadable, so these are ordered by friend playtime and genre fit.");
        }
        if (out.isEmpty()) {
            for (Candidate c : shortlist) {
                out.add(new Recommendation(c.game, fallbackReason(c), (int) c.score, label(c)));
                if (out.size() >= req.count()) break;
            }
        }
        return out;
    }

    private static String label(Candidate c) {
        return "Friends: " + String.join(", ", c.hoursByFriend.keySet());
    }

    private static String fallbackReason(Candidate c) {
        List<String> who = new ArrayList<>();
        c.hoursByFriend.forEach((n, h) -> who.add(n + " has " + Math.round(h) + " h in it"));
        return String.join("; ", who) + ".";
    }
}
