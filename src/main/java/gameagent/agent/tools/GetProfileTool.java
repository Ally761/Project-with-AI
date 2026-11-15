package gameagent.agent.tools;

import java.util.Map;

import gameagent.agent.Tool;
import gameagent.agent.ToolContext;
import gameagent.agent.ToolResult;
import gameagent.profile.ProfileManager;

public class GetProfileTool implements Tool {
    private final ProfileManager profiles;

    public GetProfileTool(ProfileManager profiles) {
        this.profiles = profiles;
    }

    @Override
    public String name() { return "get_profile"; }

    @Override
    public String description() { return "Read the user's current stored preferences. args: {}"; }

    @Override
    public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
        return ToolResult.ok(profiles.profile().toPromptSummary());
    }
}
