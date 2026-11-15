package gameagent.playtime;

import java.util.List;

/**
 * The answer to "how long will this take me?". {@code generalHours} is the typical figure; {@code personalHours}
 * equals it unless the user's own history was enough to adjust it ({@code personalized}). {@code source} says where
 * the typical figure came from; {@code lowHours}/{@code highHours} (0 when unknown) are the hasty and completionist times.
 */
public record PlaytimeResult(String game, double generalHours, double personalHours, boolean personalized,
                             double paceFactor, int samples, String strategy, double loggedHours, List<String> notes,
                             String source, double lowHours, double highHours, int submissions) {
    public PlaytimeResult(String game, double generalHours, double personalHours, boolean personalized,
                          double paceFactor, int samples, String strategy, double loggedHours, List<String> notes) {
        this(game, generalHours, personalHours, personalized, paceFactor, samples, strategy, loggedHours, notes, "", 0, 0, 0);
    }

    public boolean alreadyPlayed() { return loggedHours > 0; }
}
