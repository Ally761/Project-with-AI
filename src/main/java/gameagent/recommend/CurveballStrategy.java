package gameagent.recommend;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import gameagent.data.DataSourceException;
import gameagent.data.GameDataSource;
import gameagent.llm.LLMClient;
import gameagent.llm.LLMException;
import gameagent.llm.LLMRequest;
import gameagent.llm.PromptBuilder;
import gameagent.model.Game;
import gameagent.model.Recommendation;
import gameagent.profile.Category;
import gameagent.profile.Preference;
import gameagent.profile.ProfileManager;
import gameagent.profile.Sentiment;
import gameagent.util.Json;
import gameagent.util.JsonException;
import gameagent.util.Names;

/**
 * "Curveball roulette": real trending data from the store + the LLM's taste knowledge
 * produce exactly two surprises, one popular and one niche.
 */
public class CurveballStrategy implements RecommendationStrategy {
    private final LLMClient llm;
    private final GameDataSource data;
    private final ProfileManager profiles;

    public CurveballStrategy(LLMClient llm, GameDataSource data, ProfileManager profiles) {
        this.llm = llm;
        this.data = data;
        this.profiles = profiles;
    }

    @Override
    public String name() { return "curveball"; }

    @Override
    public RecommendationResult recommend(RecommendationRequest req) throws RecommendationException {
        List<String> warnings = new ArrayList<>();
        List<Game> trending = new ArrayList<>();
        try {
            trending.addAll(data.trending(25));
        } catch (DataSourceException e) {
            warnings.add("Could not load current trends (" + e.getMessage() + "); picking from the AI's knowledge instead.");
        }
        List<String> avoid = new ArrayList<>();
        for (Preference p : profiles.profile().preferences()) if (p.category() == Category.GAME) avoid.add(p.name());
        for (var g : profiles.profile().mostPlayed(30)) avoid.add(g.name());
        List<String> shown = new ArrayList<>(profiles.profile().shownGames());
        avoid.addAll(shown.subList(Math.max(0, shown.size() - 20), shown.size()));

        Map<String, Object> o;
        try {
            String raw = llm.complete(LLMRequest.json(PromptBuilder.curveballSystem(),
                    PromptBuilder.curveballUser(profiles.profile().toPromptSummary(),
                            trending.stream().map(Game::name).toList(), avoid, req.query())));
            o = Json.parseLenientObject(raw);
        } catch (LLMException e) {
            throw new RecommendationException("The AI service is unavailable: " + e.getMessage(), e);
        } catch (JsonException e) {
            throw new RecommendationException("The AI returned an unreadable curveball; please roll again.", e);
        }

        Map<String, Object> pop = Json.obj(o, "popular");
        Map<String, Object> niche = Json.obj(o, "niche");
        if (pop == null || niche == null) throw new RecommendationException("The AI did not return both a popular and a niche game; roll again.");

        Game popular = resolvePopular(Json.str(pop, "name", ""), trending, avoid, warnings);
        Game nicheGame = resolveNiche(Json.str(niche, "name", ""), warnings);
        if (popular == null || nicheGame == null) throw new RecommendationException("Could not settle on two games; roll again.");
        if (Names.norm(popular.name()).equals(Names.norm(nicheGame.name()))) {
            throw new RecommendationException("The roll produced the same game twice; roll again.");
        }

        List<Recommendation> recs = List.of(
                new Recommendation(popular, Json.str(pop, "reason", "Popular right now."), -1, "Popular"),
                new Recommendation(nicheGame, Json.str(niche, "reason", "A hidden gem."), -1, "Niche"));
        profiles.markShown(recs.stream().map(r -> r.game().name()).toList());
        return new RecommendationResult(recs, warnings);
    }

    private Game resolvePopular(String name, List<Game> trending, List<String> avoid, List<String> warnings) {
        for (Game g : trending) {
            if (Names.norm(g.name()).equals(Names.norm(name)) && !profiles.profile().owns(g.name())) return enrich(g);
        }
        // The model strayed from the list: fall back to the first trending game the user hasn't seen or disliked.
        for (Game g : trending) {
            boolean skip = profiles.profile().owns(g.name()) || avoid.stream().anyMatch(a -> Names.norm(a).equals(Names.norm(g.name())));
            boolean disliked = profiles.profile().find(Category.GAME, g.name())
                    .map(p -> p.sentiment() == Sentiment.DISLIKE).orElse(false);
            if (!skip && !disliked) return enrich(g);
        }
        if (!name.isBlank()) return lookup(name, warnings);
        return null;
    }

    private Game resolveNiche(String name, List<String> warnings) {
        if (name.isBlank()) return null;
        return lookup(name, warnings);
    }

    private Game lookup(String name, List<String> warnings) {
        try {
            Optional<Game> g = data.findByName(name);
            if (g.isPresent()) return g.get();
            warnings.add("\"" + name + "\" was not found in the Steam catalogue, so it is unverified.");
        } catch (DataSourceException e) {
            warnings.add("Game database unreachable; \"" + name + "\" is unverified.");
        }
        return Game.unverified(name.trim());
    }

    private Game enrich(Game g) {
        try {
            return data.findByName(g.name()).orElse(g);
        } catch (DataSourceException e) {
            return g;
        }
    }
}
