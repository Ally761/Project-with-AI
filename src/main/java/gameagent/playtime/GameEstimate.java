package gameagent.playtime;

import java.util.List;

/**
 * How long a game takes and what it feels like. {@code aiEstimate} is false only when the user supplied the hours,
 * so the UI can say "about" for guesses.
 */
public record GameEstimate(double hours, List<String> vibes, boolean aiEstimate) {
    public static final double DEFAULT_HOURS = 10;

    private static final GameEstimate UNKNOWN = new GameEstimate(DEFAULT_HOURS, List.of(), true);

    /** Used when nothing is known about a game: a neutral guess. Test with {@link #isUnknown()}. */
    public static GameEstimate unknown() {
        return UNKNOWN;
    }

    /** True for the placeholder: the source had no data for the game, so the number means nothing. */
    public boolean isUnknown() {
        return this == UNKNOWN;
    }

    public int minutes() {
        return (int) Math.round(hours * 60);
    }
}
