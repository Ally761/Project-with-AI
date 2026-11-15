package gameagent.calendar;

import java.util.Locale;

/** The user-facing choice of scheduling strategy. */
public enum ScheduleMode {
    FOCUS(new FocusScheduling()),
    MIX(new VarietyScheduling());

    private final SchedulingStrategy strategy;

    ScheduleMode(SchedulingStrategy strategy) {
        this.strategy = strategy;
    }

    public SchedulingStrategy strategy() { return strategy; }

    /** Lenient: "focus", "mix", "variety", "rotate"; anything else (or null) gives the default FOCUS. */
    public static ScheduleMode parse(String s) {
        if (s == null) return FOCUS;
        switch (s.trim().toLowerCase(Locale.ROOT)) {
            case "mix": case "variety": case "rotate": case "mixed": return MIX;
            default: return FOCUS;
        }
    }

    public static boolean isModeWord(String s) {
        String k = s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
        return k.equals("focus") || k.equals("mix") || k.equals("variety") || k.equals("rotate");
    }
}
