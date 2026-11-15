package gameagent.agent.tools;

import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import gameagent.agent.Tool;
import gameagent.agent.ToolContext;
import gameagent.agent.ToolResult;
import gameagent.friends.Friend;
import gameagent.friends.FriendService;
import gameagent.recommend.RecommendationException;
import gameagent.recommend.RecommendationResult;
import gameagent.recommend.RecommendationService;
import gameagent.steam.SteamProfileException;
import gameagent.util.Json;

public class FriendsTool implements Tool {
    private final FriendService friends;
    private final RecommendationService recommendations;

    public FriendsTool(FriendService friends, RecommendationService recommendations) {
        this.friends = friends;
        this.recommendations = recommendations;
    }

    @Override
    public String name() { return "friends"; }

    @Override
    public String description() {
        return "Use the user's Steam friends. args: {\"action\":\"add|list|recommend\", \"profile\":\"friend's Steam profile link (for add)\", "
                + "\"friend\":\"a friend's name (optional, for recommend)\", \"count\":1-10 (default 5)}. "
                + "\"add\" reads a friend's PUBLIC profile; \"recommend\" suggests games the friends play that suit the user (shown automatically).";
    }

    @Override
    public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
        String action = Json.str(args, "action", "list").toLowerCase(Locale.ROOT);
        try {
            switch (action) {
                case "add": {
                    Friend f = friends.add(Json.str(args, "profile", ""));
                    return ToolResult.ok("Added friend " + f.name() + " (" + f.games().size() + " visible games).");
                }
                case "recommend": {
                    RecommendationResult r = recommendations.fromFriends(Json.str(args, "friend", ""), Json.integer(args, "count", 5));
                    ctx.recommendations.addAll(r.recommendations());
                    ctx.warnings.addAll(r.warnings());
                    return ToolResult.ok(RecommendationService.summarize(r));
                }
                case "list": {
                    if (friends.friends().isEmpty()) return ToolResult.ok("The user has no friends added yet.");
                    return ToolResult.ok("Friends: " + friends.friends().stream().map(Friend::name).collect(Collectors.joining(", ")));
                }
                default:
                    return ToolResult.error("Unknown action \"" + action + "\". Use add, list or recommend.");
            }
        } catch (SteamProfileException | RecommendationException e) {
            return ToolResult.error(e.getMessage());
        }
    }
}
