package gameagent.news;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import gameagent.llm.LLMClient;
import gameagent.llm.LLMException;
import gameagent.llm.LLMRequest;
import gameagent.llm.PromptBuilder;
import gameagent.model.NewsItem;
import gameagent.profile.Preference;
import gameagent.profile.ProfileEvent;
import gameagent.profile.ProfileListener;
import gameagent.profile.ProfileManager;
import gameagent.util.Json;

/**
 * Hybrid news feature: deterministic fetching/merging/caching from several {@link NewsSource}s,
 * then personalised ranking by the LLM with a keyword-based fallback when the LLM is unavailable.
 * Observes the profile so cached rankings are dropped when preferences change.
 */
public class NewsService implements ProfileListener {
    private static final Duration FETCH_TTL = Duration.ofMinutes(10);
    private static final int LLM_WINDOW = 40;

    private final List<NewsSource> sources;
    private final LLMClient llm;
    private final ProfileManager profiles;
    private final Path cacheFile;

    private List<NewsItem> fetched;
    private Instant fetchedAt;
    private final Map<String, NewsResult> rankedCache = new HashMap<>();

    public NewsService(List<NewsSource> sources, LLMClient llm, ProfileManager profiles, Path cacheFile) {
        this.sources = sources;
        this.llm = llm;
        this.profiles = profiles;
        this.cacheFile = cacheFile;
    }

    @Override
    public synchronized void onProfileChanged(ProfileEvent event) {
        if (event.type().affectsRecommendations()) rankedCache.clear();
    }

    public synchronized NewsResult getNews(String topic, int limit) throws NewsException {
        String key = (topic == null ? "" : topic.trim().toLowerCase(Locale.ROOT)) + "#" + limit;
        NewsResult cached = rankedCache.get(key);
        if (cached != null) return cached;

        List<String> warnings = new ArrayList<>();
        boolean fromCache = false;
        if (fetched == null || fetchedAt == null || Duration.between(fetchedAt, Instant.now()).compareTo(FETCH_TTL) > 0) {
            List<NewsItem> all = new ArrayList<>();
            for (NewsSource s : sources) {
                try {
                    all.addAll(s.fetch());
                } catch (NewsException e) {
                    warnings.add("Could not load " + e.getMessage());
                }
            }
            if (!all.isEmpty()) {
                fetched = mergeAndSort(all);
                fetchedAt = Instant.now();
                writeCache(fetched, fetchedAt);
            } else {
                loadCache(); // sets fetched/fetchedAt or throws
                fromCache = true;
                warnings.add("News services unreachable; showing stored news from " + fetchedAt);
            }
        } else {
            fromCache = false;
        }

        List<NewsItem> chosen;
        boolean ai = false;
        try {
            chosen = rankWithLlm(topic, limit);
            ai = true;
        } catch (LLMException e) {
            warnings.add("AI ranking unavailable (" + e.getMessage() + "); used keyword matching instead.");
            chosen = rankByKeywords(topic, limit);
        }
        NewsResult res = new NewsResult(chosen, fromCache, fetchedAt, ai, warnings);
        if (!fromCache) rankedCache.put(key, res);
        return res;
    }

    // ---------------------------------------------------------------- merging & caching

    static List<NewsItem> mergeAndSort(List<NewsItem> all) {
        Map<String, NewsItem> byLink = new LinkedHashMap<>();
        for (NewsItem n : all) byLink.putIfAbsent(n.link(), n);
        List<NewsItem> out = new ArrayList<>(byLink.values());
        out.sort(Comparator.comparing((NewsItem n) -> n.published() == null ? Instant.EPOCH : n.published()).reversed());
        return out;
    }

    private void writeCache(List<NewsItem> items, Instant when) {
        if (cacheFile == null) return;
        try {
            List<Object> list = new ArrayList<>();
            for (NewsItem n : items) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("title", n.title());
                m.put("link", n.link());
                m.put("source", n.source());
                m.put("published", n.published() == null ? "" : n.published().toString());
                m.put("summary", n.summary());
                list.add(m);
            }
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("fetched", when.toString());
            root.put("items", list);
            if (cacheFile.getParent() != null) Files.createDirectories(cacheFile.getParent());
            Files.writeString(cacheFile, Json.pretty(root), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // cache is best effort
        }
    }

    private void loadCache() throws NewsException {
        if (cacheFile == null || !Files.isRegularFile(cacheFile)) throw new NewsException("No news available: feeds unreachable and nothing stored yet.");
        try {
            Map<String, Object> root = Json.parseObject(Files.readString(cacheFile, StandardCharsets.UTF_8));
            List<NewsItem> items = new ArrayList<>();
            for (Object o : Json.list(root, "items")) {
                Map<String, Object> m = Json.asObject(o);
                Instant pub = null;
                try {
                    String p = Json.str(m, "published", "");
                    if (!p.isBlank()) pub = Instant.parse(p);
                } catch (Exception ignored) { }
                items.add(new NewsItem(Json.str(m, "title", ""), Json.str(m, "link", ""), Json.str(m, "source", ""), pub, Json.str(m, "summary", ""), ""));
            }
            fetched = items;
            fetchedAt = Instant.parse(Json.str(root, "fetched", Instant.now().toString()));
        } catch (Exception e) {
            throw new NewsException("No news available: feeds unreachable and stored news unreadable.", e);
        }
    }

    // ---------------------------------------------------------------- ranking

    private List<NewsItem> rankWithLlm(String topic, int limit) throws LLMException {
        List<NewsItem> window = fetched.subList(0, Math.min(LLM_WINDOW, fetched.size()));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < window.size(); i++) {
            NewsItem n = window.get(i);
            String sum = n.summary().length() > 120 ? n.summary().substring(0, 120) : n.summary();
            sb.append(i).append(". [").append(n.source()).append("] ").append(n.title()).append(" - ").append(sum).append('\n');
        }
        String raw = llm.complete(LLMRequest.json(PromptBuilder.newsSystem(),
                PromptBuilder.newsUser(profiles.profile().toPromptSummary(), topic, sb.toString(), limit)));
        List<NewsItem> out = new ArrayList<>();
        try {
            Map<String, Object> o = Json.parseLenientObject(raw);
            Set<Integer> seen = new LinkedHashSet<>();
            for (Object p : Json.list(o, "picks")) {
                Map<String, Object> pm = Json.asObject(p);
                if (pm == null) continue;
                int idx = Json.integer(pm, "index", -1);
                if (idx < 0 || idx >= window.size() || !seen.add(idx)) continue;
                out.add(window.get(idx).withWhy(Json.str(pm, "why", "")));
                if (out.size() >= limit) break;
            }
        } catch (gameagent.util.JsonException e) {
            throw new LLMException("AI returned unreadable news ranking");
        }
        if (out.isEmpty()) throw new LLMException("AI picked no usable headlines");
        return out;
    }

    List<NewsItem> rankByKeywords(String topic, int limit) {
        List<Preference> prefs = profiles.profile().preferences();
        String[] topicWords = topic == null || topic.isBlank() ? new String[0] : topic.toLowerCase(Locale.ROOT).split("\\W+");
        record Scored(NewsItem item, double score, String why) {}
        List<Scored> scored = new ArrayList<>();
        for (NewsItem n : fetched) {
            String hay = (n.title() + " " + n.summary()).toLowerCase(Locale.ROOT);
            double score = 0;
            List<String> hits = new ArrayList<>();
            for (Preference p : prefs) {
                if (p.name().length() < 3) continue;
                if (hay.contains(p.name().toLowerCase(Locale.ROOT))) {
                    score += p.sentiment() == gameagent.profile.Sentiment.LIKE ? p.weight() : -p.weight();
                    if (p.sentiment() == gameagent.profile.Sentiment.LIKE) hits.add(p.name());
                }
            }
            for (String w : topicWords) if (w.length() >= 3 && hay.contains(w)) { score += 4; hits.add(w); }
            scored.add(new Scored(n, score, hits.isEmpty() ? "Latest gaming news" : "Matches your interest: " + String.join(", ", hits)));
        }
        // stable sort keeps newest-first order among equal scores
        scored.sort(Comparator.comparingDouble(Scored::score).reversed());
        List<NewsItem> out = new ArrayList<>();
        for (Scored s : scored) {
            if (s.score() < 0) continue;
            out.add(s.item().withWhy(s.why()));
            if (out.size() >= limit) break;
        }
        return out;
    }
}
