package gameagent.recommend;

/** What the user asked for: optional free-text query, how many games, and (for friend picks) which friend, or blank for all. */
public record RecommendationRequest(String query, int count, String friend) {
    public RecommendationRequest(String query, int count) {
        this(query, count, "");
    }

    public static RecommendationRequest of(String query, int count) {
        return of(query, count, "");
    }

    public static RecommendationRequest of(String query, int count, String friend) {
        return new RecommendationRequest(query == null ? "" : query.trim(), Math.max(1, Math.min(count, 10)), friend == null ? "" : friend.trim());
    }
}
