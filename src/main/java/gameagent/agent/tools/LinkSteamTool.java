package gameagent.agent.tools;

import gameagent.model.SteamLinkResult;
import java.util.Map;
import java.util.stream.Collectors;

import gameagent.agent.Tool;
import gameagent.agent.ToolContext;
import gameagent.agent.ToolResult;
import gameagent.steam.SteamLinkService;
import gameagent.steam.SteamProfileException;
import gameagent.util.Json;

public class LinkSteamTool implements Tool {
    private final SteamLinkService steam;

    public LinkSteamTool(SteamLinkService steam) {
        this.steam = steam;
    }

    @Override
    public String name() { return "link_steam"; }

    @Override
    public String description() {
        return "Link the user's PUBLIC Steam profile to read their game library and playtime, which updates their tastes. "
                + "Use only when the user gives a Steam profile link, 17-digit Steam ID or custom URL name, or asks to refresh the link. "
                + "args: {\"profile\":\"<link, id or name>\"} (leave \"profile\" out to refresh the profile that is already linked)";
    }

    @Override
    public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
        String profile = Json.str(args, "profile", "").trim();
        try {
            SteamLinkResult r = profile.isEmpty() ? steam.refresh() : steam.link(profile);
            ctx.profileChanges.addAll(r.applied());
            ctx.warnings.addAll(r.warnings());
            String top = r.topPlayed().stream().limit(5).map(g -> g.name() + " (" + Math.round(g.hours()) + " h)")
                    .collect(Collectors.joining(", "));
            return ToolResult.ok("Linked Steam profile \"" + r.link().personaName() + "\": " + r.libraryCount() + " most-played games read, "
                    + Math.round(r.totalHours()) + " hours. Most played: " + (top.isEmpty() ? "none" : top) + ". "
                    + (r.applied().isEmpty() ? "No new tastes were added." : r.applied().size() + " preferences were added to the profile.")
                    + " Games known to be owned will not be recommended.");
        } catch (SteamProfileException e) {
            return ToolResult.error(e.getMessage());
        }
    }
}
