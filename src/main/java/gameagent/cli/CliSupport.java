package gameagent.cli;

import java.util.List;

import gameagent.calendar.CalendarPlan;
import gameagent.model.NewsItem;
import gameagent.model.Recommendation;

/** Shared output helpers used by commands and by the chat loop. */
public final class CliSupport {
    private CliSupport() {}

    public static void showRecommendations(CliContext ctx, List<Recommendation> recs, List<String> warnings) {
        ctx.println("");
        Printer.recommendations(ctx.out(), recs);
        for (String w : warnings) ctx.println("Note: " + w);
    }

    public static void showNews(CliContext ctx, List<NewsItem> items, List<String> warnings) {
        ctx.println("");
        Printer.news(ctx.out(), items);
        for (String w : warnings) ctx.println("Note: " + w);
    }

    public static void showPlaytime(CliContext ctx, gameagent.playtime.PlaytimeResult r) {
        ctx.println("");
        Printer.playtime(ctx.out(), r);
    }

    public static void showCalendar(CliContext ctx, CalendarPlan plan) {
        ctx.println("");
        Printer.calendar(ctx.out(), plan);
    }
}
