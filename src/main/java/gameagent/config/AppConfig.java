package gameagent.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Application settings. Values come from (highest priority first):
 * real environment variables, then the local ".env" file, then built-in defaults.
 * Secrets such as the API key live only in ".env" / the environment, never in source control.
 */
public final class AppConfig {
    public record FeedSpec(String name, String url) {}

    private final Map<String, String> values;

    public AppConfig(Map<String, String> values) {
        this.values = new HashMap<>(values);
    }

    /** Loads .env (if present) and overlays environment variables. */
    public static AppConfig load(Path envFile) {
        Map<String, String> merged = new HashMap<>();
        if (envFile != null && Files.isRegularFile(envFile)) {
            try {
                merged.putAll(parseEnv(Files.readAllLines(envFile, StandardCharsets.UTF_8)));
            } catch (IOException e) {
                System.err.println("Warning: could not read " + envFile + ": " + e.getMessage());
            }
        }
        merged.putAll(System.getenv());
        return new AppConfig(merged);
    }

    static Map<String, String> parseEnv(List<String> lines) {
        Map<String, String> out = new HashMap<>();
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String key = line.substring(0, eq).trim();
            String val = line.substring(eq + 1).trim();
            if (val.length() >= 2 && ((val.startsWith("\"") && val.endsWith("\"")) || (val.startsWith("'") && val.endsWith("'")))) {
                val = val.substring(1, val.length() - 1);
            }
            out.put(key, val);
        }
        return out;
    }

    public String get(String key, String def) {
        String v = values.get(key);
        return (v == null || v.isBlank()) ? def : v.trim();
    }

    public int getInt(String key, int def) {
        try {
            return Integer.parseInt(get(key, String.valueOf(def)));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public String provider() { return get("LLM_PROVIDER", "gemini").toLowerCase(); }

    /** Treats the placeholder from .env.example as "no key". */
    public String apiKey() {
        String k = get("LLM_API_KEY", get("GEMINI_API_KEY", ""));
        return k.equals("YOUR_LOCAL_API_KEY_HERE") ? "" : k;
    }

    public String model() { return get("LLM_MODEL", "gemini-3.7-flash"); }
    public String baseUrl() { return get("LLM_BASE_URL", ""); }
    /**
     * Gemini 3 thinking depth: minimal | low | medium | high. Lower = faster and lighter on the server.
     * "default" / "off" / "none" leaves the model's own default (which is "high" for Gemini 3).
     */
    public String thinkingLevel() {
        String v = get("LLM_THINKING_LEVEL", "low").toLowerCase();
        return (v.equals("default") || v.equals("off") || v.equals("none")) ? "" : v;
    }

    /** Gemini 2.5 thinking token budget (0 = off for Flash, -1 = dynamic). Unset = model default. */
    public Integer thinkingBudget() {
        String v = get("LLM_THINKING_BUDGET", "");
        try {
            return v.isEmpty() ? null : Integer.valueOf(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Models tried in order when the primary is overloaded. "none" disables. Gemini defaults to two other current models. */
    public List<String> fallbackModels() {
        String def = provider().equals("gemini") ? "gemini-3.5-flash,gemini-3.5-flash-lite" : "";
        String raw = get("LLM_FALLBACK_MODELS", def);
        List<String> out = new ArrayList<>();
        if (raw.equalsIgnoreCase("none")) return out;
        for (String m : raw.split(",")) {
            String t = m.trim();
            if (!t.isEmpty() && !t.equals(model()) && !out.contains(t)) out.add(t);
        }
        return out;
    }

    /** IGDB (Twitch developer app) credentials for third-party playtime data; blank when not configured. */
    public String igdbClientId() { return placeholderToEmpty(get("IGDB_CLIENT_ID", "")); }
    public String igdbClientSecret() { return placeholderToEmpty(get("IGDB_CLIENT_SECRET", "")); }

    private static String placeholderToEmpty(String v) { return v.startsWith("YOUR_") ? "" : v; }

    public int minIntervalMs() { return getInt("LLM_MIN_INTERVAL_MS", 4000); }
    public Path dataDir() { return Path.of(get("DATA_DIR", "data")); }
    public String steamCountry() { return get("STEAM_COUNTRY", "US"); }

    public List<FeedSpec> newsFeeds() {
        String raw = get("NEWS_FEEDS", "");
        List<FeedSpec> feeds = new ArrayList<>();
        if (!raw.isBlank()) {
            for (String part : raw.split(",")) {
                String[] nv = part.split("\\|", 2);
                if (nv.length == 2 && !nv[1].isBlank()) feeds.add(new FeedSpec(nv[0].trim(), nv[1].trim()));
            }
        }
        if (feeds.isEmpty()) {
            feeds.add(new FeedSpec("PC Gamer", "https://www.pcgamer.com/rss/"));
            feeds.add(new FeedSpec("Rock Paper Shotgun", "https://www.rockpapershotgun.com/feed"));
            feeds.add(new FeedSpec("Eurogamer", "https://www.eurogamer.net/feed"));
        }
        return feeds;
    }
}
