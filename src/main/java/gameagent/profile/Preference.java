package gameagent.profile;

import java.time.Instant;

/** One thing the user likes or dislikes, with a strength (1-5) that grows when it is reinforced. */
public final class Preference {
    public static final double MAX_WEIGHT = 5.0;

    private final Category category;
    private final String name;
    private Sentiment sentiment;
    private double weight;
    private String source;
    private Instant updated;

    public Preference(Category category, String name, Sentiment sentiment, double weight, String source, Instant updated) {
        this.category = category;
        this.name = name;
        this.sentiment = sentiment;
        this.weight = weight;
        this.source = source;
        this.updated = updated;
    }

    public Category category() { return category; }
    public String name() { return name; }
    public Sentiment sentiment() { return sentiment; }
    public double weight() { return weight; }
    public String source() { return source; }
    public Instant updated() { return updated; }

    void reinforce(String source) {
        this.weight = Math.min(MAX_WEIGHT, weight + 0.5);
        this.source = source;
        this.updated = Instant.now();
    }

    void flip(Sentiment s, double newWeight, String source) {
        this.sentiment = s;
        this.weight = newWeight;
        this.source = source;
        this.updated = Instant.now();
    }

    @Override
    public String toString() {
        return name + " (" + String.format("%.1f", weight) + ")";
    }
}
