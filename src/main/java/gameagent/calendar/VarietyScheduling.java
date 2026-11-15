package gameagent.calendar;

import java.util.List;

/** Rotates between games with sessions of at most 90 minutes, so every game moves forward each week. */
public class VarietyScheduling implements SchedulingStrategy {
    static final int MAX_SESSION_MINUTES = 90;

    @Override
    public String name() { return "mix"; }

    @Override
    public List<Session> schedule(List<Occurrence> slots, List<PlanGame> games) {
        return SlotFiller.fill(slots, games, MAX_SESSION_MINUTES, true);
    }
}
