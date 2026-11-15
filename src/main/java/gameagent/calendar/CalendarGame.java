package gameagent.calendar;

/** A game the user asked to schedule. {@code hours} is their own playtime figure, or null to let the estimator decide. */
public record CalendarGame(String name, Double hours) {}
