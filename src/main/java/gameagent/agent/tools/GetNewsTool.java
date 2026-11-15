package gameagent.agent.tools;

import java.util.Map;

import gameagent.agent.Tool;
import gameagent.agent.ToolContext;
import gameagent.agent.ToolResult;
import gameagent.model.NewsItem;
import gameagent.news.NewsException;
import gameagent.news.NewsResult;
import gameagent.news.NewsService;
import gameagent.util.Json;

public class GetNewsTool implements Tool {
    private final NewsService news;

    public GetNewsTool(NewsService news) {
        this.news = news;
    }

    @Override
    public String name() { return "get_news"; }

    @Override
    public String description() {
        return "Fetch gaming news relevant to the user (shown to the user automatically). args: {\"topic\":\"optional topic\", \"limit\":1-10 (default 5)}";
    }

    @Override
    public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
        int limit = Math.max(1, Math.min(10, Json.integer(args, "limit", 5)));
        try {
            NewsResult r = news.getNews(Json.str(args, "topic", ""), limit);
            ctx.news.addAll(r.items());
            ctx.warnings.addAll(r.warnings());
            StringBuilder sb = new StringBuilder("Shown to the user: ");
            for (NewsItem n : r.items()) sb.append('"').append(n.title()).append("\"; ");
            return ToolResult.ok(sb.toString());
        } catch (NewsException e) {
            return ToolResult.error(e.getMessage());
        }
    }
}
