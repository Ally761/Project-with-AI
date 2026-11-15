package gameagent.cli.commands;

import java.util.List;
import java.util.Locale;

import gameagent.calendar.CalendarException;
import gameagent.calendar.CalendarGame;
import gameagent.calendar.CalendarPlan;
import gameagent.calendar.PlaySlot;
import gameagent.calendar.ScheduleMode;
import gameagent.cli.CliContext;
import gameagent.cli.CliSupport;

class CalendarCommand extends BaseCommand {
    CalendarCommand() {
        super("calendar", "/calendar [when <times> | add <game> [| hours] | remove <game> | games | make [weeks|month] [focus|mix] | tweak <change> | reset]",
                "Plan when to play your games over a week or a month.", "cal");
    }

    @Override
    public void execute(String args, CliContext ctx) {
        String a = args == null ? "" : args.trim();
        String[] parts = a.split("\\s+", 2);
        String sub = parts[0].toLowerCase(Locale.ROOT);
        String rest = parts.length > 1 ? parts[1].trim() : "";
        try {
            switch (sub) {
                case "" -> status(ctx);
                case "when" -> when(ctx, rest);
                case "add" -> add(ctx, rest);
                case "remove" -> ctx.println(ctx.agent().removeCalendarGame(rest) ? "Removed " + rest + "." : "\"" + rest + "\" isn't in your added games.");
                case "games" -> games(ctx);
                case "make", "create", "plan" -> make(ctx, rest);
                case "tweak", "change", "refine" -> tweak(ctx, rest);
                case "reset" -> {
                    ctx.agent().resetCalendarAdjustments();
                    ctx.println("Adjustments cleared. Run /calendar make to rebuild.");
                }
                default -> ctx.println("Try: /calendar when mon,wed 20:00-22:00 chill; sat 14:00-18:00   then   /calendar make");
            }
        } catch (CalendarException e) {
            ctx.println("! " + e.getMessage());
        }
    }

    private void status(CliContext ctx) {
        List<PlaySlot> slots = ctx.agent().availability();
        if (slots.isEmpty()) {
            ctx.println("No availability set. Tell me when you can play, e.g.:");
            ctx.println("  /calendar when mon,wed 20:00-22:00 chill; sat 14:00-18:00 intense");
            ctx.println("Then /calendar make (a week) or /calendar make month. Add games with /calendar add Celeste | 8");
            return;
        }
        ctx.println("You can play: " + String.join(", ", slots.stream().map(PlaySlot::describe).toList()));
        var plan = ctx.agent().currentCalendar();
        if (plan.isEmpty()) {
            ctx.println("No calendar yet. Use /calendar make.");
            return;
        }
        CliSupport.showCalendar(ctx, plan.get());
        if (ctx.agent().calendarIsStale()) ctx.println("(Your profile or inputs changed since this was built. /calendar make refreshes it.)");
    }

    private void when(CliContext ctx, String text) throws CalendarException {
        List<PlaySlot> slots = ctx.agent().setAvailability(text);
        ctx.println("Saved: " + String.join(", ", slots.stream().map(PlaySlot::describe).toList()));
        ctx.println("Now /calendar make to build the schedule.");
    }

    private void add(CliContext ctx, String text) throws CalendarException {
        String name = text;
        Double hours = null;
        int bar = text.lastIndexOf('|');
        if (bar >= 0) {
            name = text.substring(0, bar).trim();
            String h = text.substring(bar + 1).trim().toLowerCase(Locale.ROOT).replaceAll("\\s*(hours|hrs|hr|h)$", "");
            try {
                hours = Double.parseDouble(h);
            } catch (NumberFormatException e) {
                throw new CalendarException("\"" + h + "\" isn't a number of hours. Example: /calendar add Celeste | 8");
            }
        }
        ctx.agent().addCalendarGame(name, hours);
        ctx.println("Added " + name + (hours == null ? " (I'll estimate how long it takes)." : " (" + hours + " h)."));
    }

    private void games(CliContext ctx) {
        List<CalendarGame> games = ctx.agent().calendarGames();
        if (games.isEmpty()) ctx.println("No games added by you. Your saved recommendations are scheduled too (see /catalog).");
        for (CalendarGame g : games) ctx.println("  " + g.name() + (g.hours() == null ? "" : "  (" + g.hours() + " h)"));
    }

    private void make(CliContext ctx, String rest) throws CalendarException {
        int weeks = 1;
        ScheduleMode mode = ScheduleMode.FOCUS;
        for (String w : rest.split("\\s+")) {
            if (w.isBlank()) continue;
            String k = w.toLowerCase(Locale.ROOT);
            if (k.equals("month") || k.equals("monthly")) weeks = 4;
            else if (k.equals("week") || k.equals("weekly")) weeks = 1;
            else if (k.matches("\\d{1,2}")) weeks = Integer.parseInt(k);
            else if (ScheduleMode.isModeWord(k)) mode = ScheduleMode.parse(k);
            else throw new CalendarException("I didn't understand \"" + w + "\". Try: /calendar make month mix");
        }
        ctx.println("Estimating playtimes and building your calendar...");
        CalendarPlan plan = ctx.agent().createCalendar(weeks, mode);
        CliSupport.showCalendar(ctx, plan);
    }

    private void tweak(CliContext ctx, String change) throws CalendarException {
        ctx.println("Updating your calendar...");
        CliSupport.showCalendar(ctx, ctx.agent().refineCalendar(change));
    }
}
