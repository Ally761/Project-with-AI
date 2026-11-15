package gameagent.recommend;

/**
 * Factory Method (Creator). A recommender runs one kind of recommendation and records the outcome in the catalogue; the
 * only step that differs between kinds is which {@link RecommendationStrategy} (the product) gets created, and that
 * step is the abstract {@link #createStrategy()} which each subclass implements. Adding a new kind of recommendation
 * means adding a subclass, with no change to this class or to {@link RecommendationService}.
 */
public abstract class Recommender {
    private final GameCatalog catalog;
    private final boolean freshForCurrentProfile;

    /** @param freshForCurrentProfile whether results count as tailored to the current profile (curveballs deliberately are not) */
    protected Recommender(GameCatalog catalog, boolean freshForCurrentProfile) {
        this.catalog = catalog;
        this.freshForCurrentProfile = freshForCurrentProfile;
    }

    /** The factory method: each concrete recommender decides which strategy to build. */
    protected abstract RecommendationStrategy createStrategy();

    /** The operation built on the product: create the strategy, run it, remember the result. */
    public final RecommendationResult recommend(RecommendationRequest request) throws RecommendationException {
        RecommendationStrategy strategy = createStrategy();
        RecommendationResult result = strategy.recommend(request);
        catalog.addAll(result.recommendations(), freshForCurrentProfile);
        return result;
    }
}
