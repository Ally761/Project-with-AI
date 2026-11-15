package gameagent.playtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import gameagent.llm.LLMClient;
import gameagent.llm.LLMException;
import gameagent.llm.LLMRequest;
import gameagent.llm.PromptBuilder;
import gameagent.util.Json;
import gameagent.util.JsonException;
import gameagent.util.Names;

/** Asks the LLM for typical hours and vibes of games. Output is clamped and validated: the model's numbers are guesses. */
public class LlmPlaytimeEstimator implements PlaytimeEstimator {
    public static final Set<String> VIBES = Set.of("chill", "intense", "story", "competitive", "social", "puzzle", "short-session");
    static final double MIN_HOURS = 0.5;
    static final double MAX_HOURS = 300;

    private final LLMClient llm;

    public LlmPlaytimeEstimator(LLMClient llm) {
        this.llm = llm;
    }

    @Override
    public Map<String, GameEstimate> estimate(List<String> games) throws PlaytimeException {
        Map<String, GameEstimate> out = new LinkedHashMap<>();
        if (games.isEmpty()) return out;
        Map<String, Object> reply;
        try {
            reply = Json.parseLenientObject(llm.complete(LLMRequest.json(PromptBuilder.playtimeSystem(), PromptBuilder.playtimeUser(games))));
        } catch (LLMException e) {
            throw new PlaytimeException("The AI service is unavailable: " + e.getMessage(), e);
        } catch (JsonException e) {
            throw new PlaytimeException("The AI's playtime estimates were unreadable.", e);
        }
        Map<String, GameEstimate> byKey = new LinkedHashMap<>();
        for (Object o : Json.list(reply, "games")) {
            Map<String, Object> m = Json.asObject(o);
            String name = Json.str(m, "name");
            double hours = Json.num(m, "hours", 0);
            if (name == null || hours <= 0) continue;
            List<String> vibes = new ArrayList<>();
            for (String v : Json.strList(m, "vibes")) {
                String k = v.trim().toLowerCase(Locale.ROOT);
                if (VIBES.contains(k) && !vibes.contains(k) && vibes.size() < 3) vibes.add(k);
            }
            byKey.put(Names.norm(name), new GameEstimate(Math.max(MIN_HOURS, Math.min(MAX_HOURS, hours)), vibes, true));
        }
        for (String g : games) out.put(g, byKey.getOrDefault(Names.norm(g), GameEstimate.unknown()));
        return out;
    }
}
