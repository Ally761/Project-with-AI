package gameagent.playtime;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import gameagent.util.HttpPoster;
import gameagent.util.Json;
import gameagent.util.JsonException;
import gameagent.util.Names;

/**
 * Adapter for IGDB's documented "time to beat" data (community submissions, in seconds). Needs a free Twitch
 * developer application (client id + secret), kept in .env. Keeps to IGDB's limit of 4 requests per second.
 */
public class IgdbGameLengthSource implements GameLengthSource {
    static final String TOKEN_URL = "https://id.twitch.tv/oauth2/token";
    static final String API = "https://api.igdb.com/v4/";
    private static final long MIN_GAP_MS = 280;

    private final HttpPoster http;
    private final String clientId;
    private final String clientSecret;
    private String token;
    private long tokenValidUntil;
    private long lastCall;

    public IgdbGameLengthSource(HttpPoster http, String clientId, String clientSecret) {
        this.http = http;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    @Override
    public String name() { return "IGDB"; }

    @Override
    public synchronized Optional<GameLength> lookup(String gameName) throws PlaytimeException {
        String q = gameName.replace("\\", " ").replace("\"", " ").trim();
        if (q.isEmpty()) return Optional.empty();
        List<Object> games = array(query("games", "search \"" + q + "\"; fields name; limit 8;"));
        Long id = null;
        String matched = null;
        for (Object o : games) {
            Map<String, Object> m = Json.asObject(o);
            // Exact title only: "Hades" must never silently become "Hades II".
            if (Names.norm(Json.str(m, "name", "")).equals(Names.norm(gameName))) {
                id = (long) Json.num(m, "id", 0);
                matched = Json.str(m, "name", gameName);
                break;
            }
        }
        if (id == null || id <= 0) return Optional.empty();

        List<Object> rows;
        try {
            rows = array(query("game_time_to_beats", "fields hastily,normally,completely,count; where game_id = " + id + ";"));
        } catch (PlaytimeException e) {
            if (!e.getMessage().contains("HTTP 400")) throw e;
            rows = array(query("game_time_to_beats", "fields hastily,normally,completely,count; where game = " + id + ";"));
        }
        if (rows.isEmpty()) return Optional.empty();
        Map<String, Object> r = Json.asObject(rows.get(0));
        GameLength len = new GameLength(matched, hours(r, "hastily"), hours(r, "normally"), hours(r, "completely"),
                Json.integer(r, "count", 0), name());
        return len.usable() ? Optional.of(len) : Optional.empty();
    }

    private static double hours(Map<String, Object> r, String key) {
        return Json.num(r, key, 0) / 3600.0;
    }

    private static List<Object> array(String body) throws PlaytimeException {
        try {
            return Json.asList(Json.parse(body));
        } catch (JsonException e) {
            throw new PlaytimeException("IGDB sent a reply I couldn't read.", e);
        }
    }

    private String query(String endpoint, String apicalypse) throws PlaytimeException {
        for (int attempt = 0; attempt < 2; attempt++) {
            String tok = accessToken(attempt > 0);
            pace();
            try {
                return http.post(API + endpoint, Map.of("Client-ID", clientId, "Authorization", "Bearer " + tok,
                        "Accept", "application/json", "Content-Type", "text/plain"), apicalypse);
            } catch (HttpPoster.HttpStatusException e) {
                if (e.status() == 401 && attempt == 0) {
                    token = null; // expired or revoked: get a fresh one and retry once
                    continue;
                }
                throw new PlaytimeException(explain(e), e);
            } catch (IOException e) {
                throw new PlaytimeException("Couldn't reach IGDB: " + e.getMessage(), e);
            }
        }
        throw new PlaytimeException("IGDB rejected the Twitch credentials. Check IGDB_CLIENT_ID and IGDB_CLIENT_SECRET in .env.");
    }

    private static String explain(HttpPoster.HttpStatusException e) {
        return switch (e.status()) {
            case 401, 403 -> "IGDB rejected the credentials (" + e.getMessage() + "). Check IGDB_CLIENT_ID and IGDB_CLIENT_SECRET in .env.";
            case 429 -> "IGDB is rate limiting requests (" + e.getMessage() + "). Try again in a moment.";
            default -> "IGDB error: " + e.getMessage();
        };
    }

    private String accessToken(boolean forceNew) throws PlaytimeException {
        long now = System.currentTimeMillis();
        if (!forceNew && token != null && now < tokenValidUntil) return token;
        String form = "client_id=" + enc(clientId) + "&client_secret=" + enc(clientSecret) + "&grant_type=client_credentials";
        try {
            Map<String, Object> m = Json.parseObject(http.post(TOKEN_URL, Map.of("Content-Type", "application/x-www-form-urlencoded"), form));
            String t = Json.str(m, "access_token");
            if (t == null || t.isBlank()) throw new PlaytimeException("Twitch did not return an access token for IGDB.");
            token = t;
            tokenValidUntil = now + (long) (Json.num(m, "expires_in", 3600) * 1000) - 60_000;
            return t;
        } catch (HttpPoster.HttpStatusException e) {
            // Never include the request (it holds the secret) in the message.
            throw new PlaytimeException("Twitch refused the IGDB credentials (" + e.getMessage() + "). Check IGDB_CLIENT_ID and IGDB_CLIENT_SECRET in .env.", e);
        } catch (IOException | JsonException e) {
            throw new PlaytimeException("Couldn't get an IGDB access token from Twitch: " + e.getClass().getSimpleName(), e);
        }
    }

    private void pace() {
        long wait = lastCall + MIN_GAP_MS - System.currentTimeMillis();
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        lastCall = System.currentTimeMillis();
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
}
