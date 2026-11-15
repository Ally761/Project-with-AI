package gameagent.playtime;

import java.util.List;

/**
 * Strategy: how the general (third-party) figure becomes a figure for this user. {@link PlaytimeService} picks the
 * strategy by how much of the user's own history is available, so it never contains the arithmetic itself.
 */
public interface PlaytimeStrategy {
    String name();

    PlaytimeResult estimate(String game, GameEstimate general, List<PaceSample> samples, double loggedHours);
}
