package gameagent.recommend;

import gameagent.model.Recommendation;

/** Entry point for the kinds of recommendation: each is delegated to its {@link Recommender} (Factory Method creators). */
public class RecommendationService {
    private final Recommender personalized;
    private final Recommender curveball;
    private final Recommender friends;
    private final GameCatalog catalog;

    public RecommendationService(Recommender personalized, Recommender curveball, Recommender friends, GameCatalog catalog) {
        this.personalized = personalized;
        this.curveball = curveball;
        this.friends = friends;
        this.catalog = catalog;
    }

    public RecommendationResult personalized(String query, int count) throws RecommendationException {
        return personalized.recommend(RecommendationRequest.of(query, count));
    }

    public RecommendationResult curveball(String hint) throws RecommendationException {
        return curveball.recommend(RecommendationRequest.of(hint, 2));
    }

    /** @param friend a friend's name, or blank to use everyone on the list */
    public RecommendationResult fromFriends(String friend, int count) throws RecommendationException {
        return friends.recommend(RecommendationRequest.of("", count, friend));
    }

    public GameCatalog catalog() { return catalog; }

    /** One-line summary used as the tool observation for the chat agent. */
    public static String summarize(RecommendationResult r) {
        StringBuilder sb = new StringBuilder("Recommended and shown to the user: ");
        for (Recommendation rec : r.recommendations()) {
            sb.append(rec.game().name());
            if (rec.label() != null) sb.append(" [").append(rec.label()).append("]");
            sb.append("; ");
        }
        if (!r.warnings().isEmpty()) sb.append(" Warnings: ").append(String.join(" ", r.warnings()));
        return sb.toString();
    }
}
