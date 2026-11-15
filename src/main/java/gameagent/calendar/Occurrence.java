package gameagent.calendar;

import java.time.LocalDate;
import java.time.LocalTime;

/** One dated instance of a weekly slot inside the planned period. */
public record Occurrence(LocalDate date, LocalTime start, int minutes, String vibe) {}
