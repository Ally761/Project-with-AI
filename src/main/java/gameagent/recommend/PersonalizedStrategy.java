package gameagent.recommend;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

import gameagent.data.GameDataSource;
import gameagent.llm.LLMClient;
import gameagent.llm.LLMException;
import gameagent.llm.LLMRequest;
import gameagent.llm.PromptBuilder;
import gameagent.model.Game;
import gameagent.model.GameFeedback;
import gameagent.model.Recommendation;
import gameagent.profile.Category;
import gameagent.profile.Preference;
import gameagent.profile.ProfileManager;
import gameagent.profile.UserProfile;
import gameagent.util.Json;
import gameagent.util.JsonException;
import gameagent.util.Names;

/**
 * Multi-step recommendation pipeline (the agent's "plan"):
 * 1) LLM proposes candidates from the profile + request,
 * 2) every candidate is verified against the real game catalogue,
 * 3) LLM ranks the verified candidates and explains each choice,
 * 4) already-known games are filtered and the result is remembered so it is not repeated.
 */
public class PersonalizedStrategy implements RecommendationStrategy {
    private final LLMClient llm;
    private final GameDataSource data;
    private final ProfileManager profiles;
    private final CandidateVerifier verifier;

    public PersonalizedStrategy(LLMClient llm, GameDataSource data, ProfileManager profiles) {
        this.llm = llm;
        this.data = data;
        this.profiles = profiles;
        this.verifier = new CandidateVerifier(data);
    }

    @Override
    public String name() { return "personalized"; }

    @Override
    public RecommendationResult recommend(RecommendationRequest req) throws RecommendationException {
        UserProfile profile = profiles.profile();
        String summary = profile.toPromptSummary();
        Set<String> known = knownGameKeys(profile);
        List<String> avoidNames = avoidList(profile);
        List<String> warnings = new ArrayList<>();

        // 1. candidates
        int ask = Math.max(8, Math.min(15, req.count() * 3));
        Map<String, String> candidateWhy = new LinkedHashMap<>();
        try {
            String raw = llm.complete(LLMRequest.json(PromptBuilder.candidatesSystem(),
                    PromptBuilder.candidatesUser(summary, req.query(), ask, avoidNames)));
            Map<String, Object> o = Json.parseLenientObject(raw);
            for (Object c : Json.list(o, "candidates")) {
                Map<String, Object> m = c instanceof String ? Map.of("name", c) : Json.asObject(c);
                String n = Json.str(m, "name");
                if (n == null || n.isBlank() || known.contains(Names.norm(n)) || profile.owns(n)) continue;
                candidateWhy.putIfAbsent(n.trim(), Json.str(m, "why", ""));
            }
        } catch (LLMException e) {
            throw new RecommendationException("The AI service is unavailable: " + e.getMessage(), e);
        } catch (JsonException e) {
            throw new RecommendationException("The AI returned a malformed candidate list; please try again.", e);
        }
        if (candidateWhy.isEmpty()) throw new RecommendationException("The AI did not suggest any new games. Try giving more detail.");

        // 2. verify against the real catalogue
        CandidateVerifier.Outcome out = verifier.verify(new ArrayList<>(candidateWhy.keySet()));
        List<Game> pool = new ArrayList<>(out.verified());
        if (out.sourceDown()) {
            warnings.add("Game database unreachable: titles below come from the AI's own knowledge and are not verified.");
            pool.addAll(out.unverified());
        } else if (pool.size() < req.count()) {
            pool.addAll(out.unverified().stream().limit(req.count() - pool.size()).toList());
            if (!out.unverified().isEmpty()) warnings.add("Some titles could not be found in the Steam catalogue (e.g. console exclusives); they are marked unverified.");
        }
        pool.removeIf(g -> known.contains(Names.norm(g.name())) || profile.owns(g.name()));
        if (pool.isEmpty()) throw new RecommendationException("No suitable games found. Try a different request.");

        // 3. rank + explain
        Map<String, Game> byKey = new LinkedHashMap<>();
        StringBuilder block = new StringBuilder();
        for (Game g : pool) {
            byKey.put(Names.norm(g.name()), g);
            block.append("- ").append(g.name());
            if (!g.genres().isEmpty()) block.append(" | genres: ").append(String.join(", ", g.genres()));
            if (!g.price().isBlank()) block.append(" | price: ").append(g.price());
            if (!g.description().isBlank()) block.append(" | ").append(trim(g.description(), 160));
            if (!g.verified()) block.append(" | (unverified)");
            block.append('\n');
        }
        List<Recommendation> result = new ArrayList<>();
        try {
            String raw = llm.complete(LLMRequest.json(PromptBuilder.rankSystem(),
                    PromptBuilder.rankUser(summary, req.query(), block.toString(), req.count())));
            Map<String, Object> o = Json.parseLenientObject(raw);
            Set<String> used = new HashSet<>();
            for (Object r : Json.list(o, "recommendations")) {
                Map<String, Object> m = Json.asObject(r);
                if (m == null) continue;
                String key = Names.norm(Json.str(m, "name", ""));
                Game g = byKey.get(key);
                if (g == null || !used.add(key)) continue;
                int score = Math.max(0, Math.min(100, Json.integer(m, "score", 70)));
                result.add(new Recommendation(g, Json.str(m, "reason", candidateWhy.getOrDefault(g.name(), "")), score, null));
                if (result.size() >= req.count()) break;
            }
        } catch (LLMException e) {
            throw new RecommendationException("The AI service is unavailable: " + e.getMessage(), e);
        } catch (JsonException e) {
            warnings.add("The AI's ranking was unreadable, so games are shown in the order first proposed.");
        }
        if (result.isEmpty()) { // ranking failed or matched nothing: fall back to candidate order
            for (Game g : pool) {
                result.add(new Recommendation(g, candidateWhy.getOrDefault(g.name(), ""), 60, null));
                if (result.size() >= req.count()) break;
            }
        }

        profiles.markShown(result.stream().map(r -> r.game().name()).toList());
        return new RecommendationResult(result, warnings);
    }

    /** Normalised titles the user already has an opinion on or has rated (owned games are checked separately). */
    private static Set<String> knownGameKeys(UserProfile p) {
        Set<String> keys = new HashSet<>();
        for (Preference pr : p.preferences()) if (pr.category() == Category.GAME) keys.add(Names.norm(pr.name()));
        for (GameFeedback f : p.feedback()) keys.add(Names.norm(f.gameName()));
        return keys;
    }

    /** Titles to tell the LLM not to repeat (known + recently shown). */
    private static List<String> avoidList(UserProfile p) {
        List<String> names = new ArrayList<>();
        for (Preference pr : p.preferences()) if (pr.category() == Category.GAME) names.add(pr.name());
        for (GameFeedback f : p.feedback()) names.add(f.gameName());
        for (var g : p.mostPlayed(40)) names.add(g.name()); // owned games are filtered later too; this saves wasted candidates
        List<String> shown = new ArrayList<>(p.shownGames());
        names.addAll(shown.subList(Math.max(0, shown.size() - 30), shown.size()));
        return names.stream().distinct().toList();
    }

    private static String trim(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n - 3) + "...";
    }
}
