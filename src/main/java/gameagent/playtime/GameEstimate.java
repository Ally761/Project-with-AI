package gameagent.playtime;

import java.util.List;

/**
 * How long a game takes and what it feels like. {@code aiEstimate} is false only when the user supplied the hours,
 * so the UI can say "about" for guesses.
 */
public record GameEstimate(double hours, List<String> vibes, boolean aiEstimate) {
    public static final double DEFAULT_HOURS = 10;

    /** Used when nothing is known about a game: a neutral guess. */
    public static GameEstimate unknown() {
        return new GameEstimate(DEFAULT_HOURS, List.of(), true);
    }

    public int minutes() {
        return (int) Math.round(hours * 60);
    }
}
