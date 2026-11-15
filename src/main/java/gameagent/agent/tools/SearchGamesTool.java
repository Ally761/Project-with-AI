package gameagent.agent.tools;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import gameagent.agent.Tool;
import gameagent.agent.ToolContext;
import gameagent.agent.ToolResult;
import gameagent.data.DataSourceException;
import gameagent.data.GameDataSource;
import gameagent.model.Game;
import gameagent.util.Json;

public class SearchGamesTool implements Tool {
    private final GameDataSource data;

    public SearchGamesTool(GameDataSource data) {
        this.data = data;
    }

    @Override
    public String name() { return "search_games"; }

    @Override
    public String description() {
        return "Look up a real game in the Steam store for facts (genres, price, release date). args: {\"name\":\"game title\"}";
    }

    @Override
    public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
        String name = Json.str(args, "name", "").trim();
        if (name.isEmpty()) return ToolResult.error("Missing required argument \"name\".");
        try {
            Optional<Game> g = data.findByName(name);
            if (g.isPresent()) return ToolResult.ok(describe(g.get()));
            List<Game> near = data.search(name, 3);
            if (near.isEmpty()) return ToolResult.ok("No game called \"" + name + "\" was found in the Steam store (it may be a console exclusive).");
            StringBuilder sb = new StringBuilder("No exact match for \"" + name + "\". Closest: ");
            for (Game n : near) sb.append(n.name()).append("; ");
            return ToolResult.ok(sb.toString());
        } catch (DataSourceException e) {
            return ToolResult.error("Game database unavailable: " + e.getMessage());
        }
    }

    static String describe(Game g) {
        StringBuilder sb = new StringBuilder(g.name());
        if (!g.genres().isEmpty()) sb.append(" | genres: ").append(String.join(", ", g.genres()));
        if (!g.price().isBlank()) sb.append(" | price: ").append(g.price());
        if (!g.releaseDate().isBlank()) sb.append(" | released: ").append(g.releaseDate());
        if (!g.description().isBlank()) sb.append(" | ").append(g.description());
        return sb.toString();
    }
}
