package gameagent.playtime;

/** One piece of evidence about the user's pace: a game they own, how long it typically takes, and how long they played it. */
public record PaceSample(String game, double typicalHours, double playedHours) {
    public double ratio() { return playedHours / typicalHours; }
}
