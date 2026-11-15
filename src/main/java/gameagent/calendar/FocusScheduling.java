package gameagent.calendar;

import java.util.List;

/** Finishes one game before starting the next, in priority order (deep dives, fewer games in flight). */
public class FocusScheduling implements SchedulingStrategy {
    @Override
    public String name() { return "focus"; }

    @Override
    public List<Session> schedule(List<Occurrence> slots, List<PlanGame> games) {
        return SlotFiller.fill(slots, games, Integer.MAX_VALUE, false);
    }
}
