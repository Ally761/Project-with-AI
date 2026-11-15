package gameagent.calendar;

/** How much of a game fits into the plan. {@code estimated} is true when the hours are an AI guess rather than the user's figure. */
public record GameProgress(String name, int scheduledMinutes, int totalMinutes, boolean estimated) {
    public boolean finished() { return scheduledMinutes >= totalMinutes; }
}
