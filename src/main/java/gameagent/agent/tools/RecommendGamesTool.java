package gameagent.agent.tools;

import java.util.Map;

import gameagent.agent.Tool;
import gameagent.agent.ToolContext;
import gameagent.agent.ToolResult;
import gameagent.recommend.RecommendationException;
import gameagent.recommend.RecommendationResult;
import gameagent.recommend.RecommendationService;
import gameagent.util.Json;

public class RecommendGamesTool implements Tool {
    private final RecommendationService service;

    public RecommendGamesTool(RecommendationService service) {
        this.service = service;
    }

    @Override
    public String name() { return "recommend_games"; }

    @Override
    public String description() {
        return "Generate verified, personalised game recommendations (shown to the user automatically). "
                + "args: {\"query\":\"what the user asked for, in their words\", \"count\":1-10 (default 5)}";
    }

    @Override
    public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
        String query = Json.str(args, "query", "");
        int count = Json.integer(args, "count", 5);
        try {
            RecommendationResult r = service.personalized(query, count);
            ctx.recommendations.addAll(r.recommendations());
            ctx.warnings.addAll(r.warnings());
            return ToolResult.ok(RecommendationService.summarize(r));
        } catch (RecommendationException e) {
            return ToolResult.error(e.getMessage());
        }
    }
}
