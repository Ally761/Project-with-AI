package gameagent.recommend;

/** What the user asked for: optional free-text query and how many games. */
public record RecommendationRequest(String query, int count) {
    public static RecommendationRequest of(String query, int count) {
        return new RecommendationRequest(query == null ? "" : query.trim(), Math.max(1, Math.min(count, 10)));
    }
}
