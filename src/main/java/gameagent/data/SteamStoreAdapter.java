package gameagent.data;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import gameagent.model.Game;
import gameagent.util.HttpFetcher;
import gameagent.util.Json;
import gameagent.util.JsonException;

/**
 * Adapter: turns the public Steam Store web API (no API key needed) into our {@link Game} model,
 * so the rest of the app never sees Steam's JSON layout.
 */
public class SteamStoreAdapter implements GameDataSource {
    private static final String BASE = "https://store.steampowered.com";

    private final HttpFetcher http;
    private final String country;
    private final String baseUrl;

    public SteamStoreAdapter(HttpFetcher http, String country) {
        this(http, country, BASE);
    }

    /** Base URL override exists so tests can point at a local stub server. */
    public SteamStoreAdapter(HttpFetcher http, String country, String baseUrl) {
        this.http = http;
        this.country = country;
        this.baseUrl = baseUrl;
    }

    @Override
    public List<Game> search(String term, int limit) throws DataSourceException {
        String url = baseUrl + "/api/storesearch/?term=" + enc(term) + "&l=english&cc=" + country;
        Map<String, Object> root = getObject(url);
        List<Game> out = new ArrayList<>();
        for (Object o : Json.list(root, "items")) {
            Map<String, Object> item = Json.asObject(o);
            String type = Json.str(item, "type", "app");
            String name = Json.str(item, "name");
            long id = (long) Json.num(item, "id", 0);
            if (name == null || id == 0 || !type.equals("app")) continue;
            out.add(new Game(name, id, "", List.of(), priceOf(Json.obj(item, "price")), "", storeUrl(id), true));
            if (out.size() >= limit) break;
        }
        return out;
    }

    @Override
    public Optional<Game> findByName(String name) throws DataSourceException {
        List<Game> results = search(name, 5);
        Optional<Game> match = bestMatch(name, results);
        if (match.isEmpty()) return Optional.empty();
        return Optional.of(enrich(match.get()));
    }

    @Override
    public Optional<Game> findByAppId(long appId, String name) throws DataSourceException {
        if (appId <= 0) return Optional.empty();
        return fetchDetails(new Game(name, appId, "", List.of(), "", "", storeUrl(appId), true));
    }

    @Override
    public List<Game> trending(int limit) throws DataSourceException {
        Map<String, Object> root = getObject(baseUrl + "/api/featuredcategories?l=english&cc=" + country);
        Map<String, Object> top = Json.obj(root, "top_sellers");
        List<Game> out = new ArrayList<>();
        Set<Long> seen = new LinkedHashSet<>();
        for (Object o : Json.list(top, "items")) {
            Map<String, Object> item = Json.asObject(o);
            String name = Json.str(item, "name");
            long id = (long) Json.num(item, "id", 0);
            if (name == null || id == 0 || !seen.add(id)) continue;
            double cents = Json.num(item, "final_price", -1);
            String price = cents < 0 ? "" : (cents == 0 ? "Free" : String.format(Locale.ROOT, "%.2f %s", cents / 100.0, Json.str(item, "currency", "")));
            out.add(new Game(name, id, "", List.of(), price.trim(), "", storeUrl(id), true));
            if (out.size() >= limit) break;
        }
        return out;
    }

    /** Adds genres, description, release date and price from the appdetails endpoint. Failure keeps the basic game. */
    Game enrich(Game g) {
        try {
            return fetchDetails(g).orElse(g);
        } catch (DataSourceException e) {
            return g;
        }
    }

    /** One appdetails request; empty when the store reports no such app. */
    private Optional<Game> fetchDetails(Game g) throws DataSourceException {
        Map<String, Object> root = getObject(baseUrl + "/api/appdetails?appids=" + g.steamAppId() + "&l=english&cc=" + country);
        Map<String, Object> entry = Json.obj(root, String.valueOf(g.steamAppId()));
        if (!Json.bool(entry, "success", false)) return Optional.empty();
        Map<String, Object> data = Json.obj(entry, "data");
        List<String> genres = new ArrayList<>();
        for (Object o : Json.list(data, "genres")) {
            String d = Json.str(Json.asObject(o), "description");
            if (d != null) genres.add(d);
        }
        String price = g.price();
        if (Json.bool(data, "is_free", false)) price = "Free";
        else if (Json.obj(data, "price_overview") != null) price = Json.str(Json.obj(data, "price_overview"), "final_formatted", price);
        return Optional.of(g.withDetails(Json.str(data, "short_description", ""), genres, price,
                Json.str(Json.obj(data, "release_date"), "date", "")));
    }

    // ---------------------------------------------------------------- helpers

    /** Normalises a title for comparison: lowercase, no trademark symbols/punctuation/spaces. */
    static String norm(String s) {
        return gameagent.util.Names.norm(s);
    }

    /**
     * Picks the search hit that really is the requested game: an exact (normalised) title match, or the top hit
     * when it is the requested title plus an edition suffix such as "Hades: Game of the Year Edition".
     * Anything looser (e.g. "Hades" vs "Hades II", "Hade" vs "Hades") is rejected so a wrong game is never shown as verified.
     */
    public static Optional<Game> bestMatch(String wanted, List<Game> results) {
        String w = norm(wanted);
        if (w.isEmpty()) return Optional.empty();
        for (Game g : results) if (norm(g.name()).equals(w)) return Optional.of(g);
        if (!results.isEmpty()) {
            String top = results.get(0).name().toLowerCase(Locale.ROOT).replaceAll("[\\u2122\\u00ae\\u00a9]", "").trim();
            String want = wanted.toLowerCase(Locale.ROOT).replaceAll("[\\u2122\\u00ae\\u00a9]", "").trim();
            if (want.length() >= 4 && top.startsWith(want) && top.length() > want.length()) {
                char next = top.charAt(want.length());
                if (next == ':' || next == '(' || next == '-' || next == '\u2013') return Optional.of(results.get(0));
            }
        }
        return Optional.empty();
    }

    private static String priceOf(Map<String, Object> price) {
        if (price == null) return "";
        double cents = Json.num(price, "final", -1);
        if (cents < 0) return "";
        return cents == 0 ? "Free" : String.format(Locale.ROOT, "%.2f %s", cents / 100.0, Json.str(price, "currency", "")).trim();
    }

    private static String storeUrl(long id) {
        return BASE + "/app/" + id;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private Map<String, Object> getObject(String url) throws DataSourceException {
        try {
            return Json.parseObject(http.get(url));
        } catch (IOException e) {
            throw new DataSourceException("Steam store request failed: " + e.getMessage(), e);
        } catch (JsonException e) {
            throw new DataSourceException("Unexpected Steam response: " + e.getMessage(), e);
        }
    }
}
