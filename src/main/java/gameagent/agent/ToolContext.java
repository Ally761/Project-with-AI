package gameagent.agent;

import java.util.ArrayList;
import java.util.List;

import gameagent.calendar.CalendarPlan;
import gameagent.model.NewsItem;
import gameagent.model.Recommendation;

/**
 * Side channel for one chat turn. Tools put structured results here (recommendation cards, headlines),
 * so the UI can render them properly instead of relying on the LLM to retype them.
 */
public class ToolContext {
    public final List<Recommendation> recommendations = new ArrayList<>();
    public final List<NewsItem> news = new ArrayList<>();
    public final List<String> profileChanges = new ArrayList<>();
    public final List<String> warnings = new ArrayList<>();
    public final List<String> toolsUsed = new ArrayList<>();
    /** Set when a tool built or changed the gaming calendar this turn. */
    public CalendarPlan calendar;
    /** Set when a tool estimated how long a game takes the user. */
    public gameagent.playtime.PlaytimeResult playtime;
}
