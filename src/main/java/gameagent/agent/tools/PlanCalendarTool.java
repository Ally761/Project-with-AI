package gameagent.agent.tools;

import java.util.Map;

import gameagent.agent.Tool;
import gameagent.agent.ToolContext;
import gameagent.agent.ToolResult;
import gameagent.calendar.CalendarException;
import gameagent.calendar.CalendarPlan;
import gameagent.calendar.CalendarService;
import gameagent.calendar.Minutes;
import gameagent.calendar.GameProgress;
import gameagent.calendar.ScheduleMode;
import gameagent.util.Json;

public class PlanCalendarTool implements Tool {
    private final CalendarService calendar;

    public PlanCalendarTool(CalendarService calendar) {
        this.calendar = calendar;
    }

    @Override
    public String name() { return "plan_calendar"; }

    @Override
    public String description() {
        return "Build or adjust the user's gaming calendar (which games to play and when). The calendar is shown to the user automatically. "
                + "args: {\"availability\":\"when the user can play, in their words (optional; saved for next time)\", "
                + "\"weeks\":1-8 (1 = weekly, 4 = monthly, default 1), \"mode\":\"focus\" (finish one game at a time) or \"mix\" (rotate games), "
                + "\"adjust\":\"a change to an existing calendar, e.g. no games on Friday (optional)\"}";
    }

    @Override
    public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
        try {
            String availability = Json.str(args, "availability", "").trim();
            if (!availability.isEmpty()) calendar.setAvailability(availability);
            String adjust = Json.str(args, "adjust", "").trim();
            CalendarPlan plan;
            if (!adjust.isEmpty() && calendar.current().isPresent()) {
                plan = calendar.refineCalendar(adjust);
            } else {
                plan = calendar.createCalendar(Json.integer(args, "weeks", 1), ScheduleMode.parse(Json.str(args, "mode", "")));
            }
            ctx.calendar = plan;
            ctx.warnings.addAll(plan.warnings());
            StringBuilder sb = new StringBuilder("Calendar built for " + plan.weeks() + " week(s): " + plan.sessions().size()
                    + " sessions, " + Minutes.hours(plan.totalMinutes()) + " of play. ");
            for (GameProgress g : plan.progress()) {
                sb.append(g.name()).append(g.finished() ? " (fits)" : " (does not fully fit)").append("; ");
            }
            if (!plan.suggestions().isEmpty()) sb.append("Time is short: offer the options shown to the user.");
            return ToolResult.ok(sb.toString());
        } catch (CalendarException e) {
            return ToolResult.error(e.getMessage());
        }
    }
}
