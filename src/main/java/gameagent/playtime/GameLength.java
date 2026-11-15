package gameagent.playtime;

/**
 * Community "time to beat" figures for one game from a third-party database. Any figure can be 0 when the
 * source has no data for it. {@code normalHours} is the usual main-story/normal-pace time.
 */
public record GameLength(String matchedName, double hastyHours, double normalHours, double completeHours, int submissions, String source) {
    /** Best single "typical" figure: the normal time, else the hasty one, else 0 (no usable data). */
    public double typicalHours() {
        return normalHours > 0 ? normalHours : hastyHours;
    }

    public boolean usable() { return typicalHours() > 0; }
}
