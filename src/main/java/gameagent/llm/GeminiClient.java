package gameagent.llm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import gameagent.util.Json;
import gameagent.util.JsonException;

/** Google Gemini via the public Generative Language REST API (free tier friendly). */
public class GeminiClient implements LLMClient {
    public static final String DEFAULT_BASE = "https://generativelanguage.googleapis.com/v1beta";

    private final String apiKey;
    private final String model;
    private final String baseUrl;
    /** e.g. {"thinkingLevel":"low"} for Gemini 3, {"thinkingBudget":0} for 2.5; null = model default. */
    private final Map<String, Object> thinking;
    private volatile boolean thinkingRejected = false;

    public GeminiClient(String apiKey, String model, String baseUrl) {
        this(apiKey, model, baseUrl, null);
    }

    public GeminiClient(String apiKey, String model, String baseUrl, Map<String, Object> thinking) {
        this.apiKey = apiKey;
        this.model = model;
        this.baseUrl = (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_BASE : baseUrl.replaceAll("/+$", "");
        this.thinking = thinking == null || thinking.isEmpty() ? null : thinking;
    }

    @Override
    public String name() { return "gemini:" + model; }

    /** Builds the request body (package-private so it can be unit tested). */
    static String buildBody(LLMRequest req, String model, Map<String, Object> thinking) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (req.systemPrompt() != null && !req.systemPrompt().isBlank()) {
            body.put("systemInstruction", Map.of("parts", List.of(Map.of("text", req.systemPrompt()))));
        }
        // Gemini wants roles "user"/"model"; merge adjacent same-role turns to keep the history well formed.
        List<Map<String, Object>> contents = new ArrayList<>();
        String lastRole = null;
        StringBuilder pending = new StringBuilder();
        for (Message m : req.messages()) {
            String role = m.role() == Role.USER ? "user" : "model";
            if (lastRole != null && !lastRole.equals(role)) {
                contents.add(turn(lastRole, pending.toString()));
                pending.setLength(0);
            }
            if (pending.length() > 0) pending.append("\n\n");
            pending.append(m.content());
            lastRole = role;
        }
        if (lastRole != null) contents.add(turn(lastRole, pending.toString()));
        body.put("contents", contents);

        Map<String, Object> gen = new LinkedHashMap<>();
        // Google recommends leaving temperature at its default (1.0) for Gemini 3 models; lowering it can degrade output.
        if (!model.startsWith("gemini-3")) gen.put("temperature", req.temperature());
        if (thinking != null) gen.put("thinkingConfig", thinking);
        if (req.jsonMode()) gen.put("responseMimeType", "application/json");
        body.put("generationConfig", gen);
        return Json.stringify(body);
    }

    /** Exposed for tests in other packages. */
    public static String buildBodyForTest(LLMRequest req, String model, Map<String, Object> thinking) {
        return buildBody(req, model, thinking);
    }

    private static Map<String, Object> turn(String role, String text) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("role", role);
        t.put("parts", List.of(Map.of("text", text)));
        return t;
    }

    @Override
    public String complete(LLMRequest req) throws LLMException {
        String url = baseUrl + "/models/" + model + ":generateContent";
        Map<String, Object> useThinking = thinkingRejected ? null : thinking;
        HttpSupport.Response r = HttpSupport.postJson(url, Map.of("x-goog-api-key", apiKey), buildBody(req, model, useThinking));
        if (r.status() == 400 && useThinking != null && HttpSupport.errorMessage(r.body()).toLowerCase().contains("thinking")) {
            // This model does not accept our thinking setting: remember that and retry once without it.
            thinkingRejected = true;
            r = HttpSupport.postJson(url, Map.of("x-goog-api-key", apiKey), buildBody(req, model, null));
        }
        if (r.status() != 200) {
            throw new LLMException("Gemini error (HTTP " + r.status() + "): " + HttpSupport.errorMessage(r.body()), r.status());
        }
        return parseResponse(r.body());
    }

    static String parseResponse(String body) throws LLMException {
        try {
            Map<String, Object> root = Json.parseObject(body);
            List<Object> candidates = Json.list(root, "candidates");
            if (candidates.isEmpty()) {
                String block = Json.str(Json.obj(root, "promptFeedback"), "blockReason");
                throw new LLMException("Gemini returned no candidates" + (block != null ? " (blocked: " + block + ")" : ""));
            }
            Map<String, Object> content = Json.obj(Json.asObject(candidates.get(0)), "content");
            StringBuilder text = new StringBuilder();
            for (Object p : Json.list(content, "parts")) {
                String t = Json.str(Json.asObject(p), "text");
                if (t != null) text.append(t);
            }
            if (text.length() == 0) throw new LLMException("Gemini returned an empty response");
            return text.toString();
        } catch (JsonException e) {
            throw new LLMException("Could not parse Gemini response: " + e.getMessage());
        }
    }
}
