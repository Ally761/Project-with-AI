package gameagent.playtime;

import java.util.List;
import java.util.Map;

/**
 * Port: how long games take. The calendar depends on this interface, not on where the numbers come from,
 * so a different source (a database, a web service) can replace the AI-backed one without touching the scheduler.
 */
public interface PlaytimeEstimator {
    /** @return an estimate for every requested game, keyed by the name as requested */
    Map<String, GameEstimate> estimate(List<String> games) throws PlaytimeException;
}
