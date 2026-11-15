package gameagent.agent.tools;

import java.util.Map;

import gameagent.agent.Tool;
import gameagent.agent.ToolContext;
import gameagent.agent.ToolResult;
import gameagent.profile.ConflictResolver;
import gameagent.profile.ProfileManager;
import gameagent.profile.ProfileUpdate;

public class UpdatePreferencesTool implements Tool {
    private final ProfileManager profiles;

    public UpdatePreferencesTool(ProfileManager profiles) {
        this.profiles = profiles;
    }

    @Override
    public String name() { return "update_preferences"; }

    @Override
    public String description() {
        return "Remember what the user likes/dislikes. args: {\"likes\":[{\"category\":\"genre|tag|game|platform\",\"name\":\"...\"}], "
                + "\"dislikes\":[...], \"remove\":[...], \"notes\":[\"constraint in plain words\"]}. Newest statement wins on conflicts.";
    }

    @Override
    public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
        ProfileUpdate update = ProfileUpdate.fromJson(args, "chat");
        if (update.isEmpty()) return ToolResult.error("No valid likes, dislikes, removals or notes were provided.");
        ProfileManager.ApplyResult r = profiles.apply(update, ConflictResolver.NEWEST_WINS);
        ctx.profileChanges.addAll(r.applied());
        String obs = r.applied().isEmpty() ? "Nothing changed." : "Saved: " + String.join("; ", r.applied()) + ".";
        if (!r.skipped().isEmpty()) obs += " Skipped: " + String.join("; ", r.skipped()) + ".";
        return ToolResult.ok(obs);
    }
}
