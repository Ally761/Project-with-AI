package gameagent.calendar;

import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import gameagent.util.Names;

/** Adjustments the user asked for when refining the calendar ("no games on Friday", "sessions under an hour"). */
public record CalendarPrefs(Set<DayOfWeek> blockedDays, int maxSessionMinutes, List<String> priority, List<String> excluded) {
    public static CalendarPrefs none() {
        return new CalendarPrefs(EnumSet.noneOf(DayOfWeek.class), 0, List.of(), List.of());
    }

    public boolean isExcluded(String game) {
        for (String e : excluded) if (Names.norm(e).equals(Names.norm(game))) return true;
        return false;
    }

    /** Applies a refinement on top of existing ones: lists accumulate, a non-zero session cap replaces the old one. */
    public CalendarPrefs merge(CalendarPrefs more, boolean resetFirst) {
        if (resetFirst) return more;
        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        days.addAll(blockedDays);
        days.addAll(more.blockedDays);
        List<String> pri = new ArrayList<>(priority);
        for (String p : more.priority) if (pri.stream().noneMatch(x -> Names.norm(x).equals(Names.norm(p)))) pri.add(p);
        List<String> ex = new ArrayList<>(excluded);
        for (String p : more.excluded) if (ex.stream().noneMatch(x -> Names.norm(x).equals(Names.norm(p)))) ex.add(p);
        return new CalendarPrefs(days, more.maxSessionMinutes > 0 ? more.maxSessionMinutes : maxSessionMinutes, pri, ex);
    }

    public String describe() {
        List<String> parts = new ArrayList<>();
        if (!blockedDays.isEmpty()) parts.add("no play on " + String.join(", ", blockedDays.stream()
                .map(d -> d.name().substring(0, 1) + d.name().substring(1).toLowerCase(Locale.ROOT)).toList()));
        if (maxSessionMinutes > 0) parts.add("sessions up to " + maxSessionMinutes + " min");
        if (!priority.isEmpty()) parts.add("first: " + String.join(", ", priority));
        if (!excluded.isEmpty()) parts.add("left out: " + String.join(", ", excluded));
        return parts.isEmpty() ? "none" : String.join("; ", parts);
    }
}
