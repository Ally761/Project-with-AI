package gameagent.calendar;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.format.TextStyle;
import java.util.Locale;

/** A recurring weekly block of play time. {@code vibe} is optional ("chill", "intense"...). */
public record PlaySlot(DayOfWeek day, LocalTime start, int minutes, String vibe) {
    public LocalTime end() {
        return start.plusMinutes(minutes);
    }

    public String describe() {
        return day.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " " + start + "-" + end()
                + (vibe == null || vibe.isBlank() ? "" : " (" + vibe + ")");
    }
}
