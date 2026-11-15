package gameagent.model;

import java.time.Instant;

/** One line of the persistent interest/interaction log. */
public record LogEntry(Instant time, String type, String text) {}
