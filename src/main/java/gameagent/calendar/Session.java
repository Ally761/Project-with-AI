package gameagent.calendar;

import java.time.LocalDate;
import java.time.LocalTime;

/** One scheduled stretch of play. {@code completesGame} marks the session in which the game is expected to be finished. */
public record Session(LocalDate date, LocalTime start, int minutes, String game, boolean completesGame, String vibe) {
    public LocalTime end() {
        return start.plusMinutes(minutes);
    }
}
