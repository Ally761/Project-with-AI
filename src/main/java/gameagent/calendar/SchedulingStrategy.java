package gameagent.calendar;

import java.util.List;

/**
 * Strategy: how games are laid out over the free time. Implementations differ only in the order games are picked
 * ({@link FocusScheduling} finishes one at a time, {@link VarietyScheduling} rotates), so {@link CalendarService}
 * never changes when a new way of scheduling is added.
 */
public interface SchedulingStrategy {
    String name();

    /** Fills the dated slots in order; reduces {@link PlanGame#remaining()} by what was scheduled. */
    List<Session> schedule(List<Occurrence> slots, List<PlanGame> games);
}
