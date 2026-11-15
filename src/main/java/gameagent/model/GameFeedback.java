package gameagent.model;

import java.time.Instant;

/** A rating (1-5) and optional comment the user gave for a game they played. */
public record GameFeedback(String gameName, int rating, String comment, Instant when) {}
