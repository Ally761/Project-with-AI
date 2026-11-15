package gameagent.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import gameagent.config.AppConfig;

/** Factory: builds the configured LLM client (throttled, retrying, with model fallback). Add new providers here. */
public final class LLMClientFactory {
    private LLMClientFactory() {}

    public static LLMClient create(AppConfig cfg) {
        return create(cfg, null);
    }

    /** @param notifier receives human-readable retry/fallback messages (may be null) */
    public static LLMClient create(AppConfig cfg, Consumer<String> notifier) {
        String provider = cfg.provider();
        boolean needsKey = !(provider.equals("openai") && !cfg.baseUrl().isBlank() && isLocal(cfg.baseUrl()));
        if (needsKey && cfg.apiKey().isBlank()) {
            return new UnavailableLLMClient("No LLM API key configured. Copy .env.example to .env and set LLM_API_KEY.");
        }
        if (!provider.equals("gemini") && !provider.equals("openai")) {
            return new UnavailableLLMClient("Unknown LLM_PROVIDER '" + provider + "' (use gemini or openai).");
        }

        List<LLMClient> chain = new ArrayList<>();
        chain.add(new ResilientLLMClient(raw(cfg, cfg.model()), cfg.minIntervalMs(), 3, 2000, notifier));
        for (String m : cfg.fallbackModels()) {
            // fallbacks get fewer retries: we already waited on the primary
            chain.add(new ResilientLLMClient(raw(cfg, m), cfg.minIntervalMs(), 1, 2000, notifier));
        }
        return chain.size() == 1 ? chain.get(0) : new FallbackLLMClient(chain, notifier);
    }

    private static LLMClient raw(AppConfig cfg, String model) {
        if (cfg.provider().equals("gemini")) return new GeminiClient(cfg.apiKey(), model, cfg.baseUrl(), thinkingFor(cfg, model));
        return new OpenAICompatibleClient(cfg.apiKey(), model, cfg.baseUrl());
    }

    /** Gemini 3 models take thinkingLevel; 2.5 models take thinkingBudget (and reject thinkingLevel). */
    public static java.util.Map<String, Object> thinkingFor(AppConfig cfg, String model) {
        if (model.startsWith("gemini-3") && !cfg.thinkingLevel().isEmpty()) return java.util.Map.of("thinkingLevel", cfg.thinkingLevel());
        if (model.startsWith("gemini-2.5") && cfg.thinkingBudget() != null) return java.util.Map.of("thinkingBudget", cfg.thinkingBudget());
        return null;
    }

    private static boolean isLocal(String url) {
        return url.contains("localhost") || url.contains("127.0.0.1");
    }
}
