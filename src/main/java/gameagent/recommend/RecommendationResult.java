package gameagent.recommend;

import java.util.List;

import gameagent.model.Recommendation;

public record RecommendationResult(List<Recommendation> recommendations, List<String> warnings) {}
