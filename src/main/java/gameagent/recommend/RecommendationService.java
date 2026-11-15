package gameagent.recommend;

import gameagent.model.Recommendation;

/** Runs a strategy chosen through the factory and records the outcome in the catalogue. */
public class RecommendationService {
    private final RecommendationFactory factory;
    private final GameCatalog catalog;

    public RecommendationService(RecommendationFactory factory, GameCatalog catalog) {
        this.factory = factory;
        this.catalog = catalog;
    }

    public RecommendationResult personalized(String query, int count) throws RecommendationException {
        RecommendationResult r = factory.create(RecommendationFactory.Type.PERSONALIZED)
                .recommend(RecommendationRequest.of(query, count));
        catalog.addAll(r.recommendations(), true);
        return r;
    }

    public RecommendationResult curveball(String hint) throws RecommendationException {
        RecommendationResult r = factory.create(RecommendationFactory.Type.CURVEBALL)
                .recommend(RecommendationRequest.of(hint, 2));
        catalog.addAll(r.recommendations(), false);
        return r;
    }

    /** @param friend a friend's name, or blank to use everyone on the list */
    public RecommendationResult fromFriends(String friend, int count) throws RecommendationException {
        RecommendationResult r = factory.create(RecommendationFactory.Type.FRIENDS)
                .recommend(RecommendationRequest.of("", count, friend));
        catalog.addAll(r.recommendations(), false);
        return r;
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
