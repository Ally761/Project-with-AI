package gameagent.playtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Scales the general figure by how the user's own hours compare with typical hours in games they appear to have played
 * through once. Plain arithmetic: the median ratio, limited to a sensible range so one odd game cannot swing the result.
 */
public class PacePlaytimeStrategy implements PlaytimeStrategy {
    public static final int MIN_SAMPLES = 3;
    static final double MIN_FACTOR = 0.6;
    static final double MAX_FACTOR = 1.6;

    @Override
    public String name() { return "pace"; }

    @Override
    public PlaytimeResult estimate(String game, GameEstimate general, List<PaceSample> samples, double loggedHours) {
        List<Double> ratios = new ArrayList<>();
        for (PaceSample s : samples) ratios.add(Math.max(0.5, Math.min(2.0, s.ratio())));
        ratios.sort(Double::compare);
        int n = ratios.size();
        double median = n % 2 == 1 ? ratios.get(n / 2) : (ratios.get(n / 2 - 1) + ratios.get(n / 2)) / 2;
        double factor = Math.max(MIN_FACTOR, Math.min(MAX_FACTOR, median));
        double personal = general.hours() * factor;
        List<String> notes = new ArrayList<>();
        String how = factor > 1.05 ? "slower than" : factor < 0.95 ? "faster than" : "about the same as";
        notes.add(String.format(Locale.ROOT, "Based on %d games you've played through, you play %s the typical player (about %.0f%% of typical time).",
                n, how, factor * 100));
        return new PlaytimeResult(game, general.hours(), personal, true, factor, n, name(), loggedHours, notes);
    }
}
