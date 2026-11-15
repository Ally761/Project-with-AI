package gameagent.calendar;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic parser for the compact availability syntax, e.g.
 * {@code mon,wed 20:00-22:00 chill; sat 14:00-18:00 intense; weekdays 19:00-20:30}.
 * Free-form wording is handled by the AI in {@link CalendarService}; this class never guesses.
 */
public final class AvailabilityParser {
    private static final Pattern RANGE = Pattern.compile("(\\d{1,2})(?::(\\d{2}))?\\s*-\\s*(\\d{1,2})(?::(\\d{2}))?");
    private AvailabilityParser() {}

    public static List<PlaySlot> parse(String text) throws CalendarException {
        List<PlaySlot> out = new ArrayList<>();
        for (String part : text.split("[;\\n]")) {
            if (part.isBlank()) continue;
            Matcher m = RANGE.matcher(part);
            if (!m.find()) throw new CalendarException("I couldn't find a time range like 20:00-22:00 in \"" + part.trim() + "\".");
            LocalTime start = time(m.group(1), m.group(2));
            LocalTime end = time(m.group(3), m.group(4));
            int minutes = (int) java.time.Duration.between(start, end).toMinutes();
            if (minutes < 15) throw new CalendarException("\"" + part.trim() + "\": the end time must be at least 15 minutes after the start (and the same day).");
            String daysText = part.substring(0, m.start()).trim();
            String vibe = part.substring(m.end()).trim().replaceAll("^[,(\\-\\s]+|[)\\s]+$", "");
            List<DayOfWeek> days = days(daysText);
            if (days.isEmpty()) throw new CalendarException("Which days? e.g. \"mon,wed " + start + "-" + end + "\".");
            for (DayOfWeek d : days) out.add(new PlaySlot(d, start, minutes, vibe));
        }
        if (out.isEmpty()) throw new CalendarException("No availability found. Example: mon,wed 20:00-22:00 chill; sat 14:00-18:00 intense");
        return out;
    }

    private static LocalTime time(String h, String min) throws CalendarException {
        int hh = Integer.parseInt(h);
        int mm = min == null ? 0 : Integer.parseInt(min);
        if (hh > 23 || mm > 59) throw new CalendarException("\"" + h + (min == null ? "" : ":" + min) + "\" is not a valid time (use 24-hour, e.g. 20:00).");
        return LocalTime.of(hh, mm);
    }

    static List<DayOfWeek> days(String text) {
        List<DayOfWeek> out = new ArrayList<>();
        for (String raw : text.toLowerCase(Locale.ROOT).split("[,/&\\s]+|\\band\\b")) {
            String t = raw.trim();
            if (t.isEmpty()) continue;
            switch (t) {
                case "daily", "everyday", "every", "all" -> add(out, DayOfWeek.values());
                case "weekdays" -> add(out, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY);
                case "weekends", "weekend" -> add(out, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);
                default -> {
                    DayOfWeek d = day(t);
                    if (d != null) add(out, d);
                }
            }
        }
        return out;
    }

    /** Lenient single-day lookup: "mon", "monday", "tues". Returns null when unrecognised. */
    public static DayOfWeek day(String t) {
        String k = t.toLowerCase(Locale.ROOT);
        if (k.length() < 3) return null;
        for (DayOfWeek d : DayOfWeek.values()) {
            String full = d.name().toLowerCase(Locale.ROOT);
            if (full.startsWith(k.substring(0, 3)) && (full.startsWith(k) || k.equals(full.substring(0, 3) + "s"))) return d;
        }
        return null;
    }

    private static void add(List<DayOfWeek> list, DayOfWeek... ds) {
        for (DayOfWeek d : ds) if (!list.contains(d)) list.add(d);
    }
}
