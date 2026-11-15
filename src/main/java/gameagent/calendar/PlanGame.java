package gameagent.calendar;

import java.util.List;
import java.util.Locale;

/** A game being scheduled: how long it takes in total, how much is still unscheduled, and what it feels like. */
public final class PlanGame {
    final String name;
    final int totalMinutes;
    final List<String> vibes;
    int remaining;

    public PlanGame(String name, int totalMinutes, List<String> vibes) {
        this.name = name;
        this.totalMinutes = totalMinutes;
        this.vibes = vibes;
        this.remaining = totalMinutes;
    }

    public String name() { return name; }
    public int totalMinutes() { return totalMinutes; }
    public int remaining() { return remaining; }

    /** True when the game's vibes include (or are included in) the slot's vibe wording. */
    boolean matches(String slotVibe) {
        String v = slotVibe.toLowerCase(Locale.ROOT);
        for (String g : vibes) if (v.contains(g) || g.contains(v)) return true;
        return false;
    }
}
