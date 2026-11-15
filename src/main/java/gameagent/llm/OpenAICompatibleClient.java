package gameagent.llm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import gameagent.util.Json;
import gameagent.util.JsonException;

/**
 * Any server that speaks the OpenAI "chat/completions" protocol: OpenAI, Groq, OpenRouter, Ollama, LM Studio, ...
 * Proves the {@link LLMClient} abstraction: switching models is a config change, not a code change.
 */
public class OpenAICompatibleClient implements LLMClient {
    public static final String DEFAULT_BASE = "https://api.openai.com/v1";

    private final String apiKey;
    private final String model;
    private final String baseUrl;

    public OpenAICompatibleClient(String apiKey, String model, String baseUrl) {
        this.apiKey = apiKey;
        this.model = model;
        this.baseUrl = (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_BASE : baseUrl.replaceAll("/+$", "");
    }

    @Override
    public String name() { return "openai-compatible:" + model; }

    static String buildBody(String model, LLMRequest req) {
        List<Map<String, Object>> msgs = new ArrayList<>();
        if (req.systemPrompt() != null && !req.systemPrompt().isBlank()) {
            msgs.add(msg("system", req.systemPrompt()));
        }
        for (Message m : req.messages()) msgs.add(msg(m.role() == Role.USER ? "user" : "assistant", m.content()));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", msgs);
        body.put("temperature", req.temperature());
        if (req.jsonMode()) body.put("response_format", Map.of("type", "json_object"));
        return Json.stringify(body);
    }

    private static Map<String, Object> msg(String role, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    @Override
    public String complete(LLMRequest req) throws LLMException {
        Map<String, String> headers = apiKey.isBlank() ? Map.of() : Map.of("Authorization", "Bearer " + apiKey);
        HttpSupport.Response r = HttpSupport.postJson(baseUrl + "/chat/completions", headers, buildBody(model, req));
        if (r.status() != 200) {
            throw new LLMException("LLM error (HTTP " + r.status() + "): " + HttpSupport.errorMessage(r.body()), r.status());
        }
        try {
            Map<String, Object> root = Json.parseObject(r.body());
            List<Object> choices = Json.list(root, "choices");
            if (choices.isEmpty()) throw new LLMException("LLM returned no choices");
            String text = Json.str(Json.obj(Json.asObject(choices.get(0)), "message"), "content");
            if (text == null || text.isBlank()) throw new LLMException("LLM returned an empty response");
            return text;
        } catch (JsonException e) {
            throw new LLMException("Could not parse LLM response: " + e.getMessage());
        }
    }
}
