package gameagent.agent.tools;

import java.util.Locale;
import java.util.Map;

import gameagent.agent.Tool;
import gameagent.agent.ToolContext;
import gameagent.agent.ToolResult;
import gameagent.playtime.PlaytimeException;
import gameagent.playtime.PlaytimeResult;
import gameagent.playtime.PlaytimeService;
import gameagent.util.Json;

public class EstimatePlaytimeTool implements Tool {
    private final PlaytimeService playtime;

    public EstimatePlaytimeTool(PlaytimeService playtime) {
        this.playtime = playtime;
    }

    @Override
    public String name() { return "estimate_playtime"; }

    @Override
    public String description() {
        return "Estimate how long a game takes THIS user to finish, using typical playtime and their own play history "
                + "(shown to the user automatically). args: {\"game\":\"exact game title\"}";
    }

    @Override
    public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
        try {
            PlaytimeResult r = playtime.estimate(Json.str(args, "game", ""));
            ctx.playtime = r;
            return ToolResult.ok(String.format(Locale.ROOT, "%s: typical %.0f h, estimate for this user %.0f h (%s).", r.game(),
                    r.generalHours(), r.personalHours(), r.personalized() ? "adjusted to their pace" : "general figure, not enough personal data"));
        } catch (PlaytimeException e) {
            return ToolResult.error(e.getMessage());
        }
    }
}
