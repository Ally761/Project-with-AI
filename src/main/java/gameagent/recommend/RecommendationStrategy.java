package gameagent.recommend;

/** Strategy interface: each way of producing recommendations (personalised, curveball, friends, ...) is one implementation. */
public interface RecommendationStrategy {
    String name();

    RecommendationResult recommend(RecommendationRequest request) throws RecommendationException;
}
