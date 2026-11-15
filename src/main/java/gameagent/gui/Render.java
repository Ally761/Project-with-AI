package gameagent.gui;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;

import gameagent.calendar.CalendarPlan;
import gameagent.calendar.GameProgress;
import gameagent.calendar.Minutes;
import gameagent.calendar.PlaySlot;
import gameagent.calendar.Session;
import gameagent.model.Game;
import gameagent.model.NewsItem;
import gameagent.model.Recommendation;
import gameagent.playtime.PlaytimeResult;

/** Turns the facade's result objects into display text for the window. */
public final class Render {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private Render() {}

    public static String date(Instant t) { return t == null ? "" : DATE.format(t); }

    public static String recommendationDetail(Recommendation r) {
        Game g = r.game();
        StringBuilder s = new StringBuilder(g.name());
        if (r.label() != null && !r.label().isBlank()) s.append("   [").append(r.label()).append("]");
        if (r.matchScore() >= 0) s.append("   match ").append(r.matchScore()).append("%");
        if (!g.verified()) s.append("   (not found in the Steam store)");
        s.append("\n");
        if (!g.genres().isEmpty()) s.append("Genres: ").append(String.join(", ", g.genres())).append("\n");
        if (!g.price().isBlank()) s.append("Price: ").append(g.price()).append("\n");
        if (!g.releaseDate().isBlank()) s.append("Released: ").append(g.releaseDate()).append("\n");
        if (!g.description().isBlank()) s.append("\n").append(g.description()).append("\n");
        if (!r.reason().isBlank()) s.append("\nWhy: ").append(r.reason()).append("\n");
        if (!g.url().isBlank()) s.append("\n").append(g.url());
        return s.toString();
    }

    public static String recommendations(List<Recommendation> recs) {
        StringBuilder s = new StringBuilder();
        int i = 1;
        for (Recommendation r : recs) {
            Game g = r.game();
            s.append(i++).append(". ").append(g.name());
            if (r.label() != null && !r.label().isBlank()) s.append("  [").append(r.label()).append("]");
            if (r.matchScore() >= 0) s.append("  (match ").append(r.matchScore()).append("%)");
            if (!g.verified()) s.append("  (unverified)");
            s.append("\n");
            if (!g.genres().isEmpty()) s.append("   Genres: ").append(String.join(", ", g.genres())).append("\n");
            if (!r.reason().isBlank()) s.append("   Why: ").append(r.reason()).append("\n");
            if (!g.url().isBlank()) s.append("   ").append(g.url()).append("\n");
        }
        return s.toString();
    }

    public static String news(List<NewsItem> items) {
        StringBuilder s = new StringBuilder();
        int i = 1;
        for (NewsItem n : items) {
            s.append(i++).append(". ").append(n.title()).append("\n   ").append(n.source());
            if (n.published() != null) s.append(" - ").append(date(n.published()));
            s.append("\n");
            if (!n.why().isBlank()) s.append("   For you: ").append(n.why()).append("\n");
            s.append("   ").append(n.link()).append("\n");
        }
        return s.toString();
    }

    public static String playtime(PlaytimeResult r) {
        StringBuilder s = new StringBuilder(r.game()).append("\n\n");
        s.append(String.format(Locale.ROOT, "Typical time to beat:  about %.0f h   (source: %s)%n", r.generalHours(), r.source()));
        if (r.lowHours() > 0 && r.highHours() > 0) {
            s.append(String.format(Locale.ROOT, "Range:                 %.0f h rushed to %.0f h completionist%n", r.lowHours(), r.highHours()));
        }
        s.append(String.format(Locale.ROOT, "For you:                about %.0f h%s%n", r.personalHours(),
                r.personalized() ? "" : "   (same as typical)"));
        if (r.alreadyPlayed()) s.append(String.format(Locale.ROOT, "You have logged:        %.0f h%n", r.loggedHours()));
        if (!r.notes().isEmpty()) s.append("\n");
        for (String n : r.notes()) s.append("- ").append(n).append("\n");
        return s.toString();
    }

    public static String slots(List<PlaySlot> slots) {
        if (slots.isEmpty()) return "No availability set yet.";
        return "You can play: " + String.join(", ", slots.stream().map(PlaySlot::describe).toList());
    }

    public static String calendar(CalendarPlan plan) {
        StringBuilder s = new StringBuilder();
        s.append("Gaming calendar: ").append(plan.start()).append(" to ").append(plan.end()).append("  (")
                .append(plan.weeks()).append(plan.weeks() == 1 ? " week, " : " weeks, ")
                .append(plan.mode().strategy().name()).append(" mode, ").append(Minutes.hours(plan.totalMinutes())).append(" of play)\n");
        LocalDate last = null;
        for (Session x : plan.sessions()) {
            if (!x.date().equals(last)) {
                s.append("\n").append(x.date().getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH)).append(" ").append(x.date()).append("\n");
                last = x.date();
            }
            s.append("  ").append(x.start()).append("-").append(x.end()).append("  ").append(x.game())
                    .append("  (").append(Minutes.hours(x.minutes())).append(")");
            if (x.completesGame()) s.append("  <- finish");
            if (x.vibe() != null && !x.vibe().isBlank()) s.append("  [").append(x.vibe()).append("]");
            s.append("\n");
        }
        if (plan.sessions().isEmpty()) s.append("  (nothing scheduled)\n");
        s.append("\nProgress:\n");
        for (GameProgress g : plan.progress()) {
            s.append("  ").append(g.name()).append(": ").append(Minutes.hours(g.scheduledMinutes())).append(" of ")
                    .append(g.estimated() ? "~" : "").append(Minutes.hours(g.totalMinutes()))
                    .append(g.finished() ? "  done" : "  not finished").append("\n");
        }
        for (String w : plan.warnings()) s.append("\nNote: ").append(w);
        if (!plan.suggestions().isEmpty()) {
            s.append("\n\nOptions:\n");
            for (String o : plan.suggestions()) s.append("  - ").append(o).append("\n");
        }
        return s.toString();
    }
}
