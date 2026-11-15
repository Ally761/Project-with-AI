package gameagent.calendar;

import java.util.Locale;

/** Formats durations for people ("45 min", "2.5 h"). Shared by the service and every UI. */
public final class Minutes {
    private Minutes() {}

    public static String hours(int minutes) {
        if (minutes < 60) return minutes + " min";
        double h = minutes / 60.0;
        return (h == Math.rint(h) ? String.valueOf((int) h) : String.format(Locale.ROOT, "%.1f", h)) + " h";
    }
}
