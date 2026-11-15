package gameagent.calendar;

import java.time.LocalDate;
import java.util.List;

/** A finished schedule plus the facts a UI needs to explain it (what fits, what does not, what to do about it). */
public record CalendarPlan(LocalDate start, int weeks, ScheduleMode mode, List<Session> sessions,
                           List<GameProgress> progress, List<String> warnings, List<String> suggestions) {
    public LocalDate end() { return start.plusDays(weeks * 7L - 1); }

    public int totalMinutes() { return sessions.stream().mapToInt(Session::minutes).sum(); }
}
