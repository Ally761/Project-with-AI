package gameagent.model;

/** A game plus the explanation for why it is being suggested. {@code label} is optional (e.g. "Popular", "Niche"). */
public record Recommendation(Game game, String reason, int matchScore, String label) {}
