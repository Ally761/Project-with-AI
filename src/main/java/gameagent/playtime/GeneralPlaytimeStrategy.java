package gameagent.playtime;

import java.util.ArrayList;
import java.util.List;

/** Not enough personal data: the general figure is the answer, and the user is told that is the case. */
public class GeneralPlaytimeStrategy implements PlaytimeStrategy {
    @Override
    public String name() { return "general"; }

    @Override
    public PlaytimeResult estimate(String game, GameEstimate general, List<PaceSample> samples, double loggedHours) {
        List<String> notes = new ArrayList<>();
        notes.add(samples.isEmpty()
                ? "I have no play history of yours to compare with, so this is the typical time for most players."
                : "I only found " + samples.size() + " comparable game" + (samples.size() == 1 ? "" : "s") + " in your history (I need "
                + PacePlaytimeStrategy.MIN_SAMPLES + "), so this is the typical time for most players.");
        return new PlaytimeResult(game, general.hours(), general.hours(), false, 1.0, samples.size(), name(), loggedHours, notes);
    }
}
