package gameagent;

import gameagent.model.RefinementAnswer;
import gameagent.model.FeedbackResult;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;

import gameagent.agent.AgentController;
import gameagent.agent.AgentReply;
import gameagent.agent.FeedbackService;
import gameagent.agent.RefinementService;
import gameagent.cli.CliApp;
import gameagent.config.AppConfig;
import gameagent.data.SteamStoreAdapter;
import gameagent.llm.GeminiClient;
import gameagent.llm.LLMClient;
import gameagent.llm.LLMException;
import gameagent.llm.LLMRequest;
import gameagent.llm.Message;
import gameagent.llm.OpenAICompatibleClient;
import gameagent.llm.ResilientLLMClient;
import gameagent.model.Game;
import gameagent.model.NewsItem;
import gameagent.model.OwnedGame;
import gameagent.model.Recommendation;
import gameagent.news.NewsResult;
import gameagent.news.NewsSource;
import gameagent.news.RssNewsSource;
import gameagent.profile.Category;
import gameagent.profile.ConflictResolver;
import gameagent.profile.ProfileManager;
import gameagent.profile.ProfileUpdate;
import gameagent.profile.Sentiment;
import gameagent.recommend.RecommendationException;
import gameagent.recommend.RecommendationResult;
import gameagent.steam.SteamCommunityAdapter;
import gameagent.steam.SteamLinkService;
import gameagent.steam.SteamProfileException;
import gameagent.steam.SteamProfileRef;
import gameagent.util.Json;

/** Dependency-free test runner. Run with test.bat / test.sh (see README). */
public class AllTests {
    static int passed = 0, failed = 0;
    static final List<String> failures = new ArrayList<>();

    interface Body { void run() throws Exception; }

    static void test(String name, Body b) {
        try {
            b.run();
            passed++;
            System.out.println("  ok    " + name);
        } catch (Throwable t) {
            failed++;
            failures.add(name + ": " + t);
            System.out.println("  FAIL  " + name + " -> " + t);
            if (!(t instanceof AssertionError)) t.printStackTrace(System.out);
        }
    }

    static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
    }

    static void eq(Object expected, Object actual, String msg) {
        if (expected == null ? actual != null : !expected.equals(actual)) throw new AssertionError(msg + " expected=<" + expected + "> actual=<" + actual + ">");
    }

    interface Call { String run() throws LLMException; }

    static LLMClient stub(String name, Call c) {
        return new LLMClient() {
            public String complete(LLMRequest r) throws LLMException { return c.run(); }
            public String name() { return name; }
        };
    }

    static Path tmp() throws Exception { return Files.createTempDirectory("gameagent-test"); }

    static AgentController controller(Fakes.ScriptedLLM llm, Fakes.FakeSteam steam, List<NewsSource> news, Path dir) {
        return controller(llm, steam, new Fakes.FakeCommunity(), news, dir);
    }

    static AgentController controller(Fakes.ScriptedLLM llm, Fakes.FakeSteam steam, Fakes.FakeCommunity community, List<NewsSource> news, Path dir) {
        return AppContext.build(llm, new SteamStoreAdapter(steam, "US"), new SteamCommunityAdapter(community), news, dir);
    }

    static List<NewsSource> rssNews() {
        return List.of(new RssNewsSource("Fake RSS", "http://feed/rss", url -> Fakes.RSS),
                new RssNewsSource("Fake Atom", "http://feed/atom", url -> Fakes.ATOM));
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Json / config");
        test("json round trip and escapes", () -> {
            Map<String, Object> m = Json.parseObject("{\"a\":[1,2.5,\"x\\n\\u0041\"],\"b\":{\"c\":true,\"d\":null}}");
            eq("{\"a\":[1,2.5,\"x\\nA\"],\"b\":{\"c\":true,\"d\":null}}", Json.stringify(m), "stringify");
            eq(2.5, ((List<?>) m.get("a")).get(1), "number");
        });
        test("json rejects garbage; lenient extraction strips fences", () -> {
            try { Json.parse("{\"a\":"); check(false, "should throw"); } catch (gameagent.util.JsonException expected) { }
            eq("v", Json.str(Json.parseLenientObject("Sure!\n```json\n{\"k\":\"v\"}\n```"), "k"), "lenient");
        });
        test("env parsing: comments, quotes, placeholder key treated as missing", () -> {
            AppConfig c = new AppConfig(Map.of("LLM_API_KEY", "YOUR_LOCAL_API_KEY_HERE", "LLM_MODEL", "m"));
            eq("", c.apiKey(), "placeholder");
            eq(2, c.newsFeeds().size() > 0 ? 2 : 0, "default feeds");
            Path d = tmp();
            Files.writeString(d.resolve(".env"), "# c\nLLM_API_KEY=\"abc\"\nLLM_MODEL=gemini-x\n\nBAD LINE\n");
            AppConfig loaded = AppConfig.load(d.resolve(".env"));
            eq("abc", loaded.apiKey(), "quoted key");
            eq("gemini-x", loaded.model(), "model");
        });

        System.out.println("LLM clients (against local stub HTTP servers)");
        test("gemini: request shape, header, response parsing", () -> {
            AtomicReference<String> body = new AtomicReference<>(), key = new AtomicReference<>(), path = new AtomicReference<>();
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            s.createContext("/", ex -> {
                body.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                key.set(ex.getRequestHeaders().getFirst("x-goog-api-key"));
                path.set(ex.getRequestURI().getPath());
                byte[] out = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"{\\\"ok\\\":true}\"}],\"role\":\"model\"}}]}".getBytes();
                ex.sendResponseHeaders(200, out.length);
                ex.getResponseBody().write(out);
                ex.close();
            });
            s.start();
            try {
                GeminiClient g = new GeminiClient("SECRET", "gemini-test", "http://127.0.0.1:" + s.getAddress().getPort() + "/v1beta");
                String r = g.complete(new LLMRequest("SYS", List.of(Message.user("a"), Message.user("b"), Message.assistant("c"), Message.user("d")), true, 0.3));
                eq("{\"ok\":true}", r, "text");
                eq("SECRET", key.get(), "api key header");
                eq("/v1beta/models/gemini-test:generateContent", path.get(), "path");
                Map<String, Object> sent = Json.parseObject(body.get());
                eq("application/json", Json.str(Json.obj(sent, "generationConfig"), "responseMimeType"), "json mode");
                eq("SYS", Json.str(Json.asObject(Json.list(Json.obj(sent, "systemInstruction"), "parts").get(0)), "text"), "system");
                eq(3, Json.list(sent, "contents").size(), "adjacent user turns merged -> user, model, user");
                check(!body.get().contains("SECRET"), "key must never be in the body");
            } finally { s.stop(0); }
        });
        test("openai-compatible: bearer auth, messages, parsing", () -> {
            AtomicReference<String> body = new AtomicReference<>(), auth = new AtomicReference<>();
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            s.createContext("/", ex -> {
                body.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                auth.set(ex.getRequestHeaders().getFirst("Authorization"));
                byte[] out = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"hello\"}}]}".getBytes();
                ex.sendResponseHeaders(200, out.length);
                ex.getResponseBody().write(out);
                ex.close();
            });
            s.start();
            try {
                OpenAICompatibleClient c = new OpenAICompatibleClient("K", "gpt-test", "http://127.0.0.1:" + s.getAddress().getPort() + "/v1");
                eq("hello", c.complete(LLMRequest.json("SYS", "hi")), "content");
                eq("Bearer K", auth.get(), "auth");
                Map<String, Object> sent = Json.parseObject(body.get());
                eq("system", Json.str(Json.asObject(Json.list(sent, "messages").get(0)), "role"), "system first");
                eq("json_object", Json.str(Json.obj(sent, "response_format"), "type"), "json mode");
            } finally { s.stop(0); }
        });
        test("resilient client retries 429 then succeeds; does not retry 400", () -> {
            AtomicInteger calls = new AtomicInteger();
            LLMClient flaky = new LLMClient() {
                public String complete(LLMRequest r) throws LLMException {
                    if (calls.incrementAndGet() < 3) throw new LLMException("rate limited", 429);
                    return "fine";
                }
                public String name() { return "flaky"; }
            };
            eq("fine", new ResilientLLMClient(flaky, 0, 3, 1).complete(LLMRequest.json("s", "u")), "eventual success");
            eq(3, calls.get(), "attempts");
            AtomicInteger c2 = new AtomicInteger();
            LLMClient bad = new LLMClient() {
                public String complete(LLMRequest r) throws LLMException { c2.incrementAndGet(); throw new LLMException("bad key", 400); }
                public String name() { return "bad"; }
            };
            try { new ResilientLLMClient(bad, 0, 3, 1).complete(LLMRequest.json("s", "u")); check(false, "should throw"); } catch (LLMException e) { eq(400, e.status(), "status"); }
            eq(1, c2.get(), "no retry on 400");
        });
        test("gemini thinking: level for gemini-3, budget for 2.5, none when disabled; temperature omitted for gemini-3", () -> {
            AppConfig cfg = new AppConfig(Map.of("LLM_API_KEY", "k"));
            eq(Map.of("thinkingLevel", "low"), gameagent.llm.LLMClientFactory.thinkingFor(cfg, "gemini-3.8-flash"), "gemini 3 -> level low by default");
            check(gameagent.llm.LLMClientFactory.thinkingFor(cfg, "gemini-2.5-flash") == null, "2.5 must not get thinkingLevel");
            eq(Map.of("thinkingBudget", 0), gameagent.llm.LLMClientFactory.thinkingFor(new AppConfig(Map.of("LLM_THINKING_BUDGET", "0")), "gemini-2.5-flash"), "2.5 budget");
            check(gameagent.llm.LLMClientFactory.thinkingFor(new AppConfig(Map.of("LLM_THINKING_LEVEL", "default")), "gemini-3.5-flash") == null, "disabled");

            AtomicReference<String> body = new AtomicReference<>();
            AtomicInteger hits = new AtomicInteger();
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            s.createContext("/", ex -> {
                String b = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                body.set(b);
                boolean rejects = hits.incrementAndGet() == 1 && b.contains("thinkingConfig");
                byte[] out = (rejects ? "{\"error\":{\"message\":\"Thinking level is not supported for this model\"}}"
                        : "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"fine\"}]}}]}").getBytes();
                ex.sendResponseHeaders(rejects ? 400 : 200, out.length);
                ex.getResponseBody().write(out);
                ex.close();
            });
            s.start();
            try {
                GeminiClient g = new GeminiClient("k", "gemini-3.8-flash", "http://127.0.0.1:" + s.getAddress().getPort() + "/v1beta", Map.of("thinkingLevel", "low"));
                eq("fine", g.complete(LLMRequest.json("s", "u")), "recovered after 400 about thinking");
                eq(2, hits.get(), "retried once without thinking config");
                check(!body.get().contains("thinkingConfig") && !body.get().contains("temperature"), "second body: " + body.get());
                g.complete(LLMRequest.json("s", "u"));
                eq(3, hits.get(), "remembers: no further double calls");
            } finally { s.stop(0); }
            Map<String, Object> sent = Json.parseObject(GeminiClient.buildBodyForTest(LLMRequest.json("s", "u"), "gemini-3.8-flash", Map.of("thinkingLevel", "low")));
            eq("low", Json.str(Json.obj(Json.obj(sent, "generationConfig"), "thinkingConfig"), "thinkingLevel"), "REST shape generationConfig.thinkingConfig.thinkingLevel");
            check(!Json.obj(sent, "generationConfig").containsKey("temperature"), "no temperature for gemini 3");
            check(Json.obj(Json.parseObject(GeminiClient.buildBodyForTest(LLMRequest.json("s", "u"), "gemini-2.5-flash", null)), "generationConfig").containsKey("temperature"), "temperature kept for 2.5");
        });
        test("fallback: 503 on primary switches to next model, notifies; bad key stops the chain; all-fail lists every error", () -> {
            List<String> notes = new ArrayList<>();
            LLMClient busy = stub("primary", () -> { throw new LLMException("The model is overloaded", 503); });
            AtomicInteger spareCalls = new AtomicInteger();
            LLMClient spare = stub("spare", () -> { spareCalls.incrementAndGet(); return "from spare"; });
            eq("from spare", new gameagent.llm.FallbackLLMClient(List.of(busy, spare), notes::add).complete(LLMRequest.json("s", "u")), "fell back");
            check(notes.size() == 1 && notes.get(0).contains("switching to spare"), "user is told: " + notes);

            LLMClient badKey = stub("primary", () -> { throw new LLMException("API key not valid", 400); });
            try { new gameagent.llm.FallbackLLMClient(List.of(badKey, spare), null).complete(LLMRequest.json("s", "u")); check(false, "throw"); }
            catch (LLMException e) { eq(0, spareCalls.get() - 1, "spare not called again"); check(e.getMessage().contains("API key not valid"), e.getMessage()); }

            LLMClient gone = stub("spare", () -> { throw new LLMException("model not found", 404); });
            try { new gameagent.llm.FallbackLLMClient(List.of(busy, gone), null).complete(LLMRequest.json("s", "u")); check(false, "throw"); }
            catch (LLMException e) { check(e.getMessage().contains("overloaded") && e.getMessage().contains("model not found"), "both errors shown: " + e.getMessage()); eq(503, e.status(), "first status kept"); }
        });
        test("resilient client announces each retry", () -> {
            List<String> notes = new ArrayList<>();
            AtomicInteger n = new AtomicInteger();
            LLMClient flaky = stub("m", () -> { if (n.incrementAndGet() < 3) throw new LLMException("HTTP 503 high demand", 503); return "ok"; });
            eq("ok", new ResilientLLMClient(flaky, 0, 3, 1, notes::add).complete(LLMRequest.json("s", "u")), "result");
            eq(2, notes.size(), "two retries announced");
            check(notes.get(0).contains("503") && notes.get(0).contains("retry 1/3"), notes.get(0));
        });
        test("config: fallback models default for gemini, de-duplicated, can be disabled", () -> {
            eq(List.of("gemini-3.5-flash", "gemini-3.5-flash-lite"), new AppConfig(Map.of("LLM_MODEL", "gemini-3.7-flash")).fallbackModels(), "default");
            eq(List.of("gemini-3.5-flash-lite"), new AppConfig(Map.of("LLM_MODEL", "gemini-3.5-flash")).fallbackModels(), "primary removed from fallbacks");
            eq(List.of("b"), new AppConfig(Map.of("LLM_MODEL", "a", "LLM_FALLBACK_MODELS", "a, b ,b")).fallbackModels(), "dedupe");
            eq(0, new AppConfig(Map.of("LLM_FALLBACK_MODELS", "none")).fallbackModels().size(), "disabled");
        });
        test("factory: no key -> unavailable client with clear error; unknown provider", () -> {
            LLMClient c = gameagent.llm.LLMClientFactory.create(new AppConfig(Map.of()));
            eq("unavailable", c.name(), "name");
            try { c.complete(LLMRequest.json("s", "u")); check(false, "throw"); } catch (LLMException e) { check(e.getMessage().contains(".env"), "mentions .env"); }
            eq("gemini:m", gameagent.llm.LLMClientFactory.create(new AppConfig(Map.of("LLM_API_KEY", "k", "LLM_MODEL", "m"))).name(), "gemini selected");
            eq("openai-compatible:o", gameagent.llm.LLMClientFactory.create(new AppConfig(Map.of("LLM_PROVIDER", "openai", "LLM_API_KEY", "k", "LLM_MODEL", "o"))).name(), "openai selected");
        });

        System.out.println("Steam adapter / RSS");
        test("steam: search, enrich, exact matching, trending dedupe", () -> {
            SteamStoreAdapter a = new SteamStoreAdapter(new Fakes.FakeSteam(), "US");
            Game g = a.findByName("Hades").orElseThrow();
            eq(1145360L, g.steamAppId(), "id");
            check(g.genres().contains("RPG"), "genres");
            eq("$24.99", g.price(), "price");
            check(g.verified(), "verified");
            check(a.findByName("Totally Fake Game 9000").isEmpty(), "fake game not found");
            check(a.findByName("Hade").isEmpty(), "partial of a short word must not match");
            List<Game> hits = List.of(new Game("Hades: Game of the Year Edition", 1, "", List.of(), "", "", "", true), new Game("Hades II", 2, "", List.of(), "", "", "", true));
            eq("Hades: Game of the Year Edition", SteamStoreAdapter.bestMatch("Hades", hits).orElseThrow().name(), "edition suffix accepted");
            check(SteamStoreAdapter.bestMatch("Hades", List.of(hits.get(1))).isEmpty(), "sequel is a different game");
            eq(2, a.trending(10).size(), "trending deduped");
        });
        test("steam outage surfaces as DataSourceException", () -> {
            Fakes.FakeSteam f = new Fakes.FakeSteam();
            f.down = true;
            try { new SteamStoreAdapter(f, "US").search("x", 3); check(false, "throw"); } catch (gameagent.data.DataSourceException e) { check(e.getMessage().contains("503"), "msg"); }
        });
        test("rss + atom parsing, html stripped, bad items dropped", () -> {
            List<NewsItem> items = new RssNewsSource("t", "u", x -> Fakes.RSS).fetch();
            eq(2, items.size(), "item without link dropped");
            check(!items.get(0).summary().contains("<"), "html stripped");
            check(items.get(0).summary().contains("weapons & bosses"), "entities decoded: " + items.get(0).summary());
            check(items.get(0).published() != null, "date parsed");
            eq("https://ex.com/atom1", new RssNewsSource("a", "u", x -> Fakes.ATOM).fetch().get(0).link(), "atom link");
        });

        System.out.println("Profile / observers");
        test("preferences: reinforce, conflict resolution, persistence, observer events", () -> {
            Path d = tmp();
            AgentController c = controller(new Fakes.ScriptedLLM(Fakes::smartScript), new Fakes.FakeSteam(), List.of(), d);
            c.updateProfile(new ProfileUpdate("user").like(Category.GENRE, "RPG"), ConflictResolver.KEEP_EXISTING);
            double w1 = c.profile().find(Category.GENRE, "rpg").orElseThrow().weight();
            c.updateProfile(new ProfileUpdate("user").like(Category.GENRE, "rpg"), ConflictResolver.KEEP_EXISTING);
            check(c.profile().find(Category.GENRE, "RPG").orElseThrow().weight() > w1, "reinforced");
            var keep = c.updateProfile(new ProfileUpdate("user").dislike(Category.GENRE, "RPG"), ConflictResolver.KEEP_EXISTING);
            eq(Sentiment.LIKE, c.profile().find(Category.GENRE, "RPG").orElseThrow().sentiment(), "kept like");
            eq(1, keep.skipped().size(), "skipped reported");
            c.updateProfile(new ProfileUpdate("user").dislike(Category.GENRE, "RPG"), ConflictResolver.NEWEST_WINS);
            eq(Sentiment.DISLIKE, c.profile().find(Category.GENRE, "RPG").orElseThrow().sentiment(), "flipped");
            check(Files.exists(d.resolve("profile.json")), "autosaved by observer");
            AgentController again = controller(new Fakes.ScriptedLLM(Fakes::smartScript), new Fakes.FakeSteam(), List.of(), d);
            eq(Sentiment.DISLIKE, again.profile().find(Category.GENRE, "RPG").orElseThrow().sentiment(), "reloaded from disk");
            check(!again.profile().log().isEmpty(), "interest log persisted");
        });
        test("profile update parsing is lenient about bad LLM output", () -> {
            Map<String, Object> j = Json.parseObject("{\"likes\":[\"Co-op\",{\"category\":\"Genre\",\"name\":\"RPG\"},{\"name\":\"\"},42],\"dislikes\":\"nope\",\"notes\":[\"budget under $20\"]}");
            ProfileUpdate u = ProfileUpdate.fromJson(j, "chat");
            eq(2, u.changes().size(), "valid entries only");
            eq(Category.GENRE, u.changes().get(1).category(), "category parsed");
            eq(1, u.addNotes().size(), "note");
        });

        System.out.println("Recommendations (fake LLM + fake Steam)");
        test("personalised pipeline drops hallucinations, excludes known games, ranks, saves catalog", () -> {
            Path d = tmp();
            Fakes.ScriptedLLM llm = new Fakes.ScriptedLLM(Fakes::smartScript);
            AgentController c = controller(llm, new Fakes.FakeSteam(), List.of(), d);
            c.updateProfile(new ProfileUpdate("user").like(Category.GAME, "Hades").like(Category.GENRE, "Roguelike"), ConflictResolver.NEWEST_WINS);
            RecommendationResult r = c.recommend("fast combat", 3);
            List<String> names = r.recommendations().stream().map(x -> x.game().name()).toList();
            eq(List.of("Dead Cells", "Hollow Knight", "Risk of Rain 2"), names, "ranked, verified, no Hades, no fake");
            check(r.recommendations().get(0).game().verified(), "verified");
            eq(93, r.recommendations().get(0).matchScore(), "score");
            check(!c.catalog().isStale(), "fresh catalogue");
            eq(3, c.catalog().all().size(), "catalog");
            check(Files.exists(d.resolve("catalog.json")), "catalog persisted");
            check(c.profile().shownGames().contains("Dead Cells"), "shown remembered");
            String candidatePrompt = llm.requests.get(0).messages().get(0).content();
            check(candidatePrompt.contains("fast combat") && candidatePrompt.contains("Roguelike"), "prompt carries request + profile");
            c.updateProfile(new ProfileUpdate("user").dislike(Category.TAG, "Grinding"), ConflictResolver.NEWEST_WINS);
            check(c.catalog().isStale(), "catalogue flagged stale by observer");
        });
        test("steam outage: warns and falls back to unverified; LLM outage: clear error", () -> {
            Fakes.FakeSteam steam = new Fakes.FakeSteam();
            steam.down = true;
            Fakes.ScriptedLLM llm = new Fakes.ScriptedLLM(Fakes::smartScript);
            AgentController c = controller(llm, steam, List.of(), tmp());
            RecommendationResult r = c.recommend("anything", 3);
            check(r.warnings().stream().anyMatch(w -> w.contains("unreachable")), "warning shown");
            check(r.recommendations().stream().noneMatch(x -> x.game().verified()), "marked unverified");
            llm.down = true;
            try { c.recommend("anything", 3); check(false, "throw"); } catch (RecommendationException e) { check(e.getMessage().contains("AI service"), e.getMessage()); }
        });
        test("curveball: one popular from trends + one niche, both labelled", () -> {
            AgentController c = controller(new Fakes.ScriptedLLM(Fakes::smartScript), new Fakes.FakeSteam(), List.of(), tmp());
            List<Recommendation> r = c.curveball("").recommendations();
            eq(2, r.size(), "two picks");
            eq("Popular", r.get(0).label(), "label");
            eq("Counter-Strike 2", r.get(0).game().name(), "popular from trending");
            eq("Niche", r.get(1).label(), "label");
            check(r.get(1).game().verified(), "niche verified");
        });

        System.out.println("Chat agent (tool loop)");
        test("chat: learns preferences, calls recommend tool, replies, structured output via context", () -> {
            Fakes.ScriptedLLM llm = new Fakes.ScriptedLLM(Fakes::smartScript);
            AgentController c = controller(llm, new Fakes.FakeSteam(), List.of(), tmp());
            AgentReply reply = c.chat("I loved Hades, find me something similar");
            eq(List.of("update_preferences", "recommend_games"), reply.context().toolsUsed, "tool sequence");
            eq(3, reply.context().recommendations.size(), "recs in context");
            check(reply.text().contains("three picks"), "final text");
            check(c.profile().find(Category.GAME, "Hades").isPresent(), "game remembered");
            eq(1, c.profile().notes().size(), "constraint note remembered");
            check(c.profile().log().stream().anyMatch(e -> e.type().equals("chat")), "chat logged to interest log");
            // memory: second turn includes the first exchange
            c.chat("hello again");
            LLMRequest last = llm.requests.get(llm.requests.size() - 1);
            check(last.messages().size() >= 3, "conversation memory carried over");
            check(last.systemPrompt().contains("Hades"), "system prompt reflects profile");
        });
        test("chat: malformed JSON is repaired once; still-garbage text is shown as-is", () -> {
            AtomicInteger n = new AtomicInteger();
            Fakes.ScriptedLLM llm = new Fakes.ScriptedLLM(r -> n.incrementAndGet() == 1 ? "sure thing!! not json" : "{\"tool\":null,\"reply\":\"repaired\"}");
            eq("repaired", controller(llm, new Fakes.FakeSteam(), List.of(), tmp()).chat("hi").text(), "repair");
            Fakes.ScriptedLLM garbage = new Fakes.ScriptedLLM(r -> "just plain words");
            eq("just plain words", controller(garbage, new Fakes.FakeSteam(), List.of(), tmp()).chat("hi").text(), "fallback");
        });
        test("chat: unknown tool and repeated identical call are survivable; step budget enforced", () -> {
            AtomicInteger n = new AtomicInteger();
            Fakes.ScriptedLLM llm = new Fakes.ScriptedLLM(r -> {
                if (Fakes.lastUser(r).contains("Tool budget used up")) return "{\"tool\":null,\"reply\":\"gave up politely\"}";
                n.incrementAndGet();
                return n.get() == 1 ? "{\"tool\":\"launch_missiles\",\"args\":{}}" : "{\"tool\":\"get_profile\",\"args\":{}}";
            });
            AgentReply r = controller(llm, new Fakes.FakeSteam(), List.of(), tmp()).chat("do stuff");
            eq("gave up politely", r.text(), "forced final");
            check(llm.requests.size() <= 7, "bounded calls: " + llm.requests.size());
        });
        test("chat: invalid tool args are reported back to the model, not executed", () -> {
            AtomicInteger n = new AtomicInteger();
            AtomicReference<String> seen = new AtomicReference<>("");
            Fakes.ScriptedLLM llm = new Fakes.ScriptedLLM(r -> {
                if (n.incrementAndGet() == 1) return "{\"tool\":\"update_preferences\",\"args\":{\"likes\":[]}}";
                seen.set(Fakes.lastUser(r));
                return "{\"tool\":null,\"reply\":\"ok\"}";
            });
            AgentController c = controller(llm, new Fakes.FakeSteam(), List.of(), tmp());
            c.chat("x");
            check(seen.get().startsWith("TOOL_RESULT update_preferences: ERROR"), seen.get());
            check(c.profile().preferences().isEmpty(), "nothing stored");
        });
        test("chat: LLM outage raises LLMException (CLI shows friendly message)", () -> {
            Fakes.ScriptedLLM llm = new Fakes.ScriptedLLM(Fakes::smartScript);
            llm.down = true;
            try { controller(llm, new Fakes.FakeSteam(), List.of(), tmp()).chat("hi"); check(false, "throw"); } catch (LLMException e) { check(true, ""); }
        });

        System.out.println("News / feedback / refinement");
        test("news: merged, AI-ranked, bad indices ignored; keyword fallback when LLM down; stored copy when feeds down", () -> {
            Path d = tmp();
            Fakes.ScriptedLLM llm = new Fakes.ScriptedLLM(Fakes::smartScript);
            AgentController c = controller(llm, new Fakes.FakeSteam(), rssNews(), d);
            NewsResult r = c.news("", 3);
            check(r.aiRanked(), "ai ranked");
            eq(2, r.items().size(), "two valid picks (index 99 dropped)");
            check(Files.exists(d.resolve("news-cache.json")), "cached to disk");

            llm.down = true;
            c.updateProfile(new ProfileUpdate("user").like(Category.GENRE, "roguelike"), ConflictResolver.NEWEST_WINS); // clears ranking cache
            NewsResult kw = c.news("", 3);
            check(!kw.aiRanked(), "fallback");
            check(kw.items().get(0).title().contains("roguelike"), "keyword match first: " + kw.items().get(0).title());
            check(kw.warnings().stream().anyMatch(w -> w.contains("keyword")), "explains fallback");

            List<NewsSource> dead = List.of(new RssNewsSource("Dead", "u", x -> { throw new java.io.IOException("HTTP 500"); }));
            AgentController offline = controller(llm, new Fakes.FakeSteam(), dead, d); // same dir -> stored news
            NewsResult stored = offline.news("", 3);
            check(stored.fromCache() && stored.fetchedAt() != null, "served stored news with timestamp");
            try { controller(llm, new Fakes.FakeSteam(), dead, tmp()).news("", 3); check(false, "throw"); } catch (gameagent.news.NewsException e) { check(true, ""); }
        });
        test("feedback: rating drives deterministic updates, comment goes through AI, conflicts flagged", () -> {
            Fakes.ScriptedLLM llm = new Fakes.ScriptedLLM(Fakes::smartScript);
            AgentController c = controller(llm, new Fakes.FakeSteam(), List.of(), tmp());
            FeedbackResult r = c.submitFeedback("Hades", 5, "great combat, weak story");
            eq(Sentiment.LIKE, c.profile().find(Category.GAME, "Hades").orElseThrow().sentiment(), "liked game");
            check(c.profile().find(Category.GENRE, "RPG").isPresent(), "genres from store data liked");
            check(c.profile().find(Category.TAG, "Weak story").map(p -> p.sentiment() == Sentiment.DISLIKE).orElse(false), "AI extracted dislike");
            FeedbackResult conflict = c.submitFeedback("Hades", 1, "");
            check(conflict.warnings().stream().anyMatch(w -> w.contains("conflicts")), "conflict warning");
            eq(1, c.profile().feedback().size(), "latest replaces earlier");
            eq(Sentiment.DISLIKE, c.profile().find(Category.GAME, "Hades").orElseThrow().sentiment(), "newest wins");
            try { c.submitFeedback("Hades", 9, ""); check(false, "throw"); } catch (IllegalArgumentException e) { check(true, ""); }
            llm.down = true;
            FeedbackResult degraded = c.submitFeedback("Dead Cells", 4, "fun");
            check(degraded.warnings().stream().anyMatch(w -> w.contains("could not be analysed")), "degrades gracefully");
            check(c.profile().find(Category.GAME, "Dead Cells").isPresent(), "rating still applied");
        });
        test("refinement: gaps -> questions -> answers -> profile updated; skip all = no AI call", () -> {
            Fakes.ScriptedLLM llm = new Fakes.ScriptedLLM(Fakes::smartScript);
            AgentController c = controller(llm, new Fakes.FakeSteam(), List.of(), tmp());
            List<String> qs = c.refinementQuestions(4);
            eq(3, qs.size(), "questions");
            int calls = llm.requests.size();
            c.submitRefinement(List.of(new RefinementAnswer(qs.get(0), " "), new RefinementAnswer(qs.get(1), "")));
            eq(calls, llm.requests.size(), "blank answers: no LLM call");
            var res = c.submitRefinement(List.of(new RefinementAnswer(qs.get(0), "PC, mostly"), new RefinementAnswer(qs.get(1), "about 45 minutes")));
            check(res.changedAnything(), "applied");
            check(c.profile().find(Category.PLATFORM, "PC").isPresent(), "platform stored");
            check(c.profile().notes().stream().anyMatch(n -> n.contains("45")), "note stored");
        });

        System.out.println("Steam profile link");
        test("steam link input: links, ids and names parse; other hosts and junk are rejected", () -> {
            eq("/id/hadesfan", SteamProfileRef.parse("https://steamcommunity.com/id/hadesfan/").communityPath(), "https link");
            eq("/id/hadesfan", SteamProfileRef.parse("steamcommunity.com/id/hadesfan/games/?tab=all").communityPath(), "no scheme, extra path");
            eq("/profiles/76561198000000001", SteamProfileRef.parse("http://www.steamcommunity.com/profiles/76561198000000001").communityPath(), "numeric link");
            eq("/profiles/76561198000000001", SteamProfileRef.parse("76561198000000001").communityPath(), "bare id");
            eq("/id/hadesfan", SteamProfileRef.parse("hadesfan").communityPath(), "bare name");
            eq("/id/x_y-z", SteamProfileRef.parse("  <https://steamcommunity.com/id/x_y-z>  ").communityPath(), "wrapped in brackets");
            for (String bad : new String[]{"", "   ", "https://evil.com/id/hadesfan", "https://steamcommunity.com.evil.com/id/x",
                    "https://steamcommunity.com/profiles/123", "bad name!", "https://store.steampowered.com/app/1145360", "https://steamcommunity.com/groups/abc"}) {
                try {
                    SteamProfileRef.parse(bad);
                    throw new AssertionError("should have been rejected: " + bad);
                } catch (SteamProfileException e) {
                    eq(SteamProfileException.Reason.INVALID_INPUT, e.reason(), "reason for <" + bad + ">");
                }
            }
        });
        test("steam community adapter: reads the profile feed (commas, CDATA, duplicates) and explains every failure", () -> {
            Fakes.FakeCommunity fc = new Fakes.FakeCommunity();
            SteamCommunityAdapter a = new SteamCommunityAdapter(fc);
            var lib = a.fetchLibrary(SteamProfileRef.parse("https://steamcommunity.com/id/hadesfan"));
            eq(Fakes.FakeCommunity.ID, lib.steamId(), "steam id");
            eq("Hades Fan", lib.personaName(), "display name");
            eq(6, lib.games().size(), "duplicate app row dropped");
            check(!lib.complete(), "only the most-played list is visible without signing in");
            OwnedGame hk = lib.games().get(0);
            eq("Hollow Knight", hk.name(), "first game");
            check(Math.abs(hk.hours() - 1034.2) < 1e-9, "thousands separator parsed: " + hk.hours());
            check(Math.abs(hk.hoursRecent() - 3.5) < 1e-9, "recent hours");
            eq(367520L, hk.appId(), "app id taken from the game link");
            check(fc.urls.size() == 1 && fc.urls.get(0).endsWith("/id/hadesfan/?xml=1"), "one request to the public profile feed: " + fc.urls);

            String[][] failures = {
                    {"privateguy", "PROFILE_PRIVATE", "Privacy Settings"},
                    {"hiddengames", "GAMES_PRIVATE", "Game details"},
                    {"nobody", "NOT_FOUND", "couldn't find"},
            };
            for (String[] f : failures) {
                try {
                    a.fetchLibrary(SteamProfileRef.parse(f[0]));
                    throw new AssertionError("expected failure for " + f[0]);
                } catch (SteamProfileException e) {
                    eq(f[1], e.reason().name(), "reason for " + f[0]);
                    check(e.getMessage().contains(f[2]), "message tells the user what to do: " + e.getMessage());
                }
            }
            fc.rateLimited = true;
            try { a.fetchLibrary(SteamProfileRef.parse("hadesfan")); throw new AssertionError("rate limit"); }
            catch (SteamProfileException e) { eq(SteamProfileException.Reason.UNAVAILABLE, e.reason(), "429"); check(e.getMessage().contains("rate-limiting"), e.getMessage()); }
            fc.rateLimited = false;
            fc.down = true;
            try { a.fetchLibrary(SteamProfileRef.parse("hadesfan")); throw new AssertionError("outage"); }
            catch (SteamProfileException e) { eq(SteamProfileException.Reason.UNAVAILABLE, e.reason(), "outage"); }
            try { new SteamCommunityAdapter(url -> "<html><body>Sorry!</body></html>").fetchLibrary(SteamProfileRef.parse("hadesfan")); throw new AssertionError("garbage"); }
            catch (SteamProfileException e) { eq(SteamProfileException.Reason.UNREADABLE, e.reason(), "not xml"); check(e.getMessage().contains("web page"), "says what came back: " + e.getMessage()); }
        });
        test("steam link: imports most-played games and shared genres, never overrides the user, refresh adds nothing twice", () -> {
            AgentController c = controller(new Fakes.ScriptedLLM(Fakes::smartScript), new Fakes.FakeSteam(), List.of(), tmp());
            c.updateProfile(new ProfileUpdate("user").dislike(Category.GAME, "Counter-Strike 2").dislike(Category.GENRE, "Indie"), ConflictResolver.NEWEST_WINS);
            var r = c.linkSteam("https://steamcommunity.com/id/hadesfan");
            eq(5, r.libraryCount(), "games read (tools such as Wallpaper Engine are not counted)");
            eq("Hades Fan", r.link().personaName(), "persona");
            eq("Hollow Knight", r.topPlayed().get(0).name(), "most played first");
            for (String liked : new String[]{"Hollow Knight", "Hades", "Celeste"}) {
                var p = c.profile().find(Category.GAME, liked);
                check(p.isPresent() && p.get().sentiment() == Sentiment.LIKE && p.get().source().equals("steam"), "liked from playtime: " + liked);
                check(Math.abs(p.get().weight() - 2.5) < 1e-9, "steam weight 2.5 for " + liked);
            }
            for (String not : new String[]{"Dead Cells", "Wallpaper Engine"}) {
                check(c.profile().find(Category.GAME, not).isEmpty(), "barely played / not a game is not a taste: " + not);
            }
            check(c.profile().find(Category.GAME, "Counter-Strike 2").get().sentiment() == Sentiment.DISLIKE, "user's dislike of a played game stays");
            check(c.profile().find(Category.GENRE, "Action").map(p -> p.sentiment() == Sentiment.LIKE && p.source().equals("steam")).orElse(false), "shared genre liked");
            check(c.profile().find(Category.GENRE, "Indie").get().sentiment() == Sentiment.DISLIKE, "user's genre dislike stays");
            check(c.profile().find(Category.GENRE, "Adventure").isEmpty(), "one-off genre is not a taste");
            check(c.profile().find(Category.GENRE, "Free To Play").isEmpty(), "store label ignored");
            check(r.applied().stream().anyMatch(x -> x.contains("Hollow Knight")), "result lists what was added");
            check(r.warnings().stream().anyMatch(w -> w.contains("most-played")), "user is told only the most-played games are visible: " + r.warnings());
            check(!c.profile().owns("Wallpaper Engine") && r.topPlayed().stream().noneMatch(g -> g.name().equals("Wallpaper Engine")), "tools are not part of the library");
            check(Math.abs(r.totalHours() - 1300.2) < 1e-6, "hours exclude tools: " + r.totalHours());

            var again = c.refreshSteam();
            check(again.applied().isEmpty(), "refresh adds nothing new: " + again.applied());
            check(Math.abs(c.profile().find(Category.GAME, "Hades").get().weight() - 2.5) < 1e-9, "weights are not inflated by refresh");
            check(c.profile().log().stream().anyMatch(e -> e.type().equals("steam")), "link logged");
        });
        test("steam library: owned games are never recommended; link survives a restart", () -> {
            Path dir = tmp();
            Fakes.ScriptedLLM llm = new Fakes.ScriptedLLM(Fakes::smartScript);
            AgentController c = controller(llm, new Fakes.FakeSteam(), List.of(), dir);
            c.linkSteam("hadesfan");
            RecommendationResult res = c.recommend("fast combat", 3);
            List<String> names = res.recommendations().stream().map(x -> x.game().name()).toList();
            check(names.contains("Risk of Rain 2"), "unowned game still recommended: " + names);
            for (String owned : new String[]{"Dead Cells", "Hollow Knight", "Hades"}) check(!names.contains(owned), "owned game excluded: " + owned);
            String avoid = llm.requests.stream().filter(r -> r.systemPrompt().contains("You suggest REAL")).map(Fakes::lastUser)
                    .flatMap(u -> java.util.Arrays.stream(u.split("\n"))).filter(l -> l.startsWith("Do NOT suggest")).findFirst().orElse("");
            check(avoid.contains("Dead Cells"), "the AI is told not to suggest owned games: " + avoid);
            check(c.profile().toPromptSummary().contains("Steam profile (linked), most played: Hollow Knight (1034 h)"), "AI sees the most-played summary: " + c.profile().toPromptSummary());

            AgentController again = controller(llm, new Fakes.FakeSteam(), List.of(), dir); // new process, same data folder
            check(again.profile().steamLink().isPresent(), "link persisted");
            eq("Hades Fan", again.profile().steamLink().get().personaName(), "persona persisted");
            eq(5, again.profile().ownedGames().size(), "library persisted");
            check(again.profile().owns("hollow knight™"), "ownership check ignores case and trademark signs");
            check(Math.abs(again.profile().ownedGames().get(0).hours() - 1034.2) < 1e-9, "hours persisted");
        });
        test("steam unlink: forgets the library; removes only imported tastes when asked", () -> {
            AgentController c = controller(new Fakes.ScriptedLLM(Fakes::smartScript), new Fakes.FakeSteam(), List.of(), tmp());
            c.updateProfile(new ProfileUpdate("user").like(Category.GAME, "Celeste"), ConflictResolver.NEWEST_WINS);
            c.linkSteam("hadesfan");
            List<String> kept = c.unlinkSteam(false);
            check(kept.isEmpty() && c.profile().steamLink().isEmpty() && c.profile().ownedGames().isEmpty(), "unlinked, nothing removed");
            check(c.profile().find(Category.GAME, "Hades").isPresent(), "imported taste kept when not asked to remove");
            check(!c.profile().owns("Hades"), "no longer treated as owned");

            c.linkSteam("hadesfan");
            List<String> removed = c.unlinkSteam(true);
            check(removed.stream().anyMatch(x -> x.contains("Hades")), "imported taste removed: " + removed);
            check(c.profile().find(Category.GAME, "Hades").isEmpty(), "Hades gone");
            check(c.profile().find(Category.GAME, "Celeste").map(p -> p.source().equals("user")).orElse(false), "what the user said is kept");
        });
        test("steam link: store outage still links the library (no genres), hidden playtime imports no tastes, private profile is explained", () -> {
            Fakes.FakeSteam store = new Fakes.FakeSteam();
            store.down = true;
            AgentController c = controller(new Fakes.ScriptedLLM(Fakes::smartScript), store, List.of(), tmp());
            var r = c.linkSteam("hadesfan");
            check(r.warnings().stream().anyMatch(w -> w.contains("store was unreachable")), "genre analysis skipped with a warning: " + r.warnings());
            check(c.profile().find(Category.GAME, "Hades").isPresent(), "games still imported");
            check(c.profile().preferences(Category.GENRE, Sentiment.LIKE).isEmpty(), "no genres without store data");

            SteamLinkService hidden = new SteamLinkService(ref -> new gameagent.steam.SteamLibrary("76561198000000009", "Shy",
                    List.of(new OwnedGame(1, "Alpha", 0, 0), new OwnedGame(2, "Beta", 0, 0)), false),
                    new SteamStoreAdapter(new Fakes.FakeSteam(), "US"), new ProfileManager(new gameagent.profile.UserProfile()));
            var h = hidden.link("shy");
            check(h.applied().isEmpty() && h.warnings().stream().anyMatch(w -> w.contains("Playtime")), "hidden playtime explained: " + h.warnings());
            eq(2, h.libraryCount(), "library still recorded");

            try { c.linkSteam("privateguy"); throw new AssertionError("private profile"); }
            catch (SteamProfileException e) { eq(SteamProfileException.Reason.PROFILE_PRIVATE, e.reason(), "private"); }
            try { c.refreshSteam(); } catch (SteamProfileException e) { throw new AssertionError("refresh of linked profile should work: " + e.getMessage()); }
            c.unlinkSteam(false);
            try { c.refreshSteam(); throw new AssertionError("refresh without link"); }
            catch (SteamProfileException e) { check(e.getMessage().contains("No Steam profile is linked"), e.getMessage()); }
        });
        test("chat: the agent links a Steam profile pasted in conversation, and relays a private-profile error", () -> {
            AtomicReference<String> toolResultSeen = new AtomicReference<>("");
            Fakes.ScriptedLLM llm = new Fakes.ScriptedLLM(r -> {
                String user = Fakes.lastUser(r);
                if (!r.systemPrompt().contains("You are GameAgent")) return "{}";
                if (user.startsWith("TOOL_RESULT link_steam")) {
                    toolResultSeen.set(user);
                    return "{\"tool\":null,\"reply\":\"Done with that.\"}";
                }
                if (user.contains("privateguy")) return "{\"tool\":\"link_steam\",\"args\":{\"profile\":\"https://steamcommunity.com/id/privateguy\"}}";
                if (user.contains("steamcommunity.com")) return "{\"tool\":\"link_steam\",\"args\":{\"profile\":\"https://steamcommunity.com/id/hadesfan\"}}";
                return "{\"tool\":null,\"reply\":\"ok\"}";
            });
            AgentController c = controller(llm, new Fakes.FakeSteam(), List.of(), tmp());
            AgentReply reply = c.chat("my steam is https://steamcommunity.com/id/hadesfan");
            eq(List.of("link_steam"), reply.context().toolsUsed, "tool used");
            check(!reply.context().profileChanges.isEmpty(), "changes reported to the UI");
            check(c.profile().steamLink().isPresent(), "linked through chat");
            check(llm.requests.get(0).systemPrompt().contains("link_steam"), "tool is described to the model");
            check(toolResultSeen.get().contains("Hollow Knight (1034 h)"), "model gets a short summary: " + toolResultSeen.get());

            c.chat("also try https://steamcommunity.com/id/privateguy");
            check(toolResultSeen.get().contains("ERROR") && toolResultSeen.get().contains("Privacy Settings"), "error with instructions goes back to the model: " + toolResultSeen.get());
            eq("Hades Fan", c.profile().steamLink().get().personaName(), "failed link leaves the existing link alone");
        });

        System.out.println("CLI (scripted session)");
        test("cli: full session through slash commands and chat", () -> {
            Fakes.ScriptedLLM llm = new Fakes.ScriptedLLM(Fakes::smartScript);
            AgentController c = controller(llm, new Fakes.FakeSteam(), rssNews(), tmp());
            String input = String.join("\n",
                    "/profile",
                    "I loved Hades, find me something similar",
                    "/profile",
                    "/dislike genre Roguelike",
                    "y",
                    "/like genre Strategy",
                    "/note I only play on weekends",
                    "/rate Hades | 5 | great combat, weak story",
                    "/recommend 3 fast combat",
                    "/curveball",
                    "/news",
                    "/refine",
                    "PC",
                    "45 min",
                    "stop",
                    "/catalog",
                    "/history 5",
                    "/forget genre Strategy",
                    "/bogus",
                    "/reset",
                    "n",
                    "/quit", "");
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            new CliApp(c, new BufferedReader(new StringReader(input)), new PrintStream(buf, true, StandardCharsets.UTF_8)).run();
            String out = buf.toString(StandardCharsets.UTF_8);
            System.out.println("    ---- CLI transcript (excerpt) ----");
            for (String line : out.split("\n")) System.out.println("    | " + line);
            for (String must : new String[]{"profile is empty", "(remembered)", "Dead Cells", "[Popular]", "[Niche]", "Switch to disliking", "Saved your 5/5",
                    "Unknown command /bogus", "Cancelled.", "Goodbye", "https://store.steampowered.com/app/588650", "For you:"}) {
                check(out.contains(must), "CLI output should contain: " + must);
            }
            check(c.profile().find(Category.GENRE, "Roguelike").map(p -> p.sentiment() == Sentiment.DISLIKE).orElse(false), "user chose to flip (answered y)");
            check(c.profile().find(Category.GENRE, "Strategy").isEmpty(), "forget worked");
        });
        test("cli: /steam links, shows status, top games, refresh, bad input and unlink", () -> {
            AgentController c = controller(new Fakes.ScriptedLLM(Fakes::smartScript), new Fakes.FakeSteam(), List.of(), tmp());
            String input = String.join("\n",
                    "/steam",
                    "/steam https://steamcommunity.com/id/hadesfan",
                    "/steam",
                    "/steam top",
                    "/profile",
                    "/steam refresh",
                    "/steam bad link!",
                    "/steam privateguy",
                    "/steam unlink",
                    "y",
                    "y",
                    "/steam",
                    "/quit", "");
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            new CliApp(c, new BufferedReader(new StringReader(input)), new PrintStream(buf, true, StandardCharsets.UTF_8)).run();
            String out = buf.toString(StandardCharsets.UTF_8);
            System.out.println("    ---- CLI transcript (excerpt) ----");
            for (String line : out.split("\n")) System.out.println("    | " + line);
            for (String must : new String[]{"No Steam profile linked", "Linked Hades Fan: read 5 games", "Most played: Hollow Knight (1034 h)",
                    "Added to your profile from your playtime", "Games I know you own won't be recommended", " 1. Hollow Knight", "Steam: linked to Hades Fan",
                    "No new tastes to add", "That doesn't look like a Steam profile link", "isn't public", "Steam profile unlinked. Removed"}) {
                check(out.contains(must), "CLI output should contain: " + must);
            }
            check(c.profile().steamLink().isEmpty(), "unlinked at the end");
            check(c.profile().find(Category.GAME, "Hades").isEmpty(), "imported tastes removed (answered y)");
        });
        test("cli: without API key the app still starts and profile commands work", () -> {
            AgentController c = AppContext.build(gameagent.llm.LLMClientFactory.create(new AppConfig(Map.of())), new SteamStoreAdapter(new Fakes.FakeSteam(), "US"),
                    new SteamCommunityAdapter(new Fakes.FakeCommunity()), List.of(), tmp());
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            new CliApp(c, new BufferedReader(new StringReader("/like game Celeste\nhello there\n/recommend\n/profile\n/quit\n")), new PrintStream(buf, true, StandardCharsets.UTF_8)).run();
            String out = buf.toString(StandardCharsets.UTF_8);
            check(out.contains("No LLM API key"), "startup warning");
            check(out.contains("AI service problem: No LLM API key configured"), "chat error is friendly");
            check(out.contains("Celeste"), "profile works offline");
        });

        System.out.println("Gaming calendar");
        test("calendar: availability syntax parses days, ranges and vibes", () -> {
            var slots = gameagent.calendar.AvailabilityParser.parse("mon,wed 20:00-22:00 chill; sat 14:00-18:00 intense; weekends 9-10");
            eq(2 + 1 + 2, slots.size(), "mon, wed, sat, plus sat+sun");
            eq(120, slots.get(0).minutes(), "2 hours");
            eq("chill", slots.get(0).vibe(), "vibe");
            eq("intense", slots.get(2).vibe(), "vibe on sat");
            for (String bad : new String[]{"", "mon", "20:00-22:00", "mon 22:00-20:00", "mon 25:00-26:00", "funday 20:00-21:00"}) {
                try { gameagent.calendar.AvailabilityParser.parse(bad); check(false, "should reject: " + bad); } catch (gameagent.calendar.CalendarException expected) { }
            }
        });
        test("calendar: free-text availability is read by the AI, bad blocks are skipped", () -> {
            AgentController c = controller(new Fakes.ScriptedLLM(Fakes::smartScript), new Fakes.FakeSteam(), List.of(), tmp());
            var slots = c.setAvailability("I'm usually free on Monday and Wednesday evenings after 8");
            eq(2, slots.size(), "the block that ends before it starts is dropped");
            eq(java.time.DayOfWeek.WEDNESDAY, slots.get(1).day(), "day names are lenient");
            eq(java.time.LocalTime.of(20, 0), slots.get(0).start(), "start");
            Fakes.ScriptedLLM down = new Fakes.ScriptedLLM(Fakes::smartScript);
            down.down = true;
            AgentController offline = controller(down, new Fakes.FakeSteam(), List.of(), tmp());
            try { offline.setAvailability("some evenings"); check(false, "needs the AI for free text"); }
            catch (gameagent.calendar.CalendarException e) { check(e.getMessage().contains("compact form"), e.getMessage()); }
            eq(1, offline.setAvailability("sun 10:00-12:00").size(), "compact form needs no AI");
        });
        test("calendar: a plan only uses available slots, respects hours, and is saved", () -> {
            Path d = tmp();
            AgentController c = controller(new Fakes.ScriptedLLM(Fakes::smartScript), new Fakes.FakeSteam(), List.of(), d);
            try { c.createCalendar(1, gameagent.calendar.ScheduleMode.FOCUS); check(false, "needs availability"); }
            catch (gameagent.calendar.CalendarException e) { check(e.getMessage().contains("when you can play"), e.getMessage()); }
            c.setAvailability("mon,wed 20:00-22:00 chill; sat 14:00-18:00");
            try { c.createCalendar(1, gameagent.calendar.ScheduleMode.FOCUS); check(false, "needs games"); }
            catch (gameagent.calendar.CalendarException e) { check(e.getMessage().contains("no games to schedule"), e.getMessage()); }
            c.addCalendarGame("Celeste", 8.0);
            c.addCalendarGame("Hades", null);
            var plan = c.createCalendar(4, gameagent.calendar.ScheduleMode.FOCUS);
            check(!plan.sessions().isEmpty(), "sessions");
            for (var s : plan.sessions()) {
                var day = s.date().getDayOfWeek();
                check(day == java.time.DayOfWeek.MONDAY || day == java.time.DayOfWeek.WEDNESDAY || day == java.time.DayOfWeek.SATURDAY, "only on free days: " + day);
                check(s.minutes() > 0 && s.minutes() <= 240, "fits a slot: " + s.minutes());
            }
            var celeste = plan.progress().stream().filter(p -> p.name().equals("Celeste")).findFirst().orElseThrow();
            eq(480, celeste.totalMinutes(), "user's own hours are used, not the estimate");
            check(!celeste.estimated(), "user figure is not marked as a guess");
            var hades = plan.progress().stream().filter(p -> p.name().equals("Hades")).findFirst().orElseThrow();
            eq(22 * 60, hades.totalMinutes(), "estimate used when no hours given");
            check(hades.estimated(), "AI figure is marked as a guess");
            check(plan.warnings().stream().anyMatch(w -> w.contains("AI estimates")), "estimates are disclosed");
            for (int i = 1; i < plan.sessions().size(); i++) {
                var a = plan.sessions().get(i - 1); var b = plan.sessions().get(i);
                check(!b.date().isBefore(a.date()) && (!b.date().equals(a.date()) || !b.start().isBefore(a.end())), "sessions never overlap and stay in order");
            }
            check(Files.exists(d.resolve("calendar.json")), "persisted");
            AgentController again = controller(new Fakes.ScriptedLLM(Fakes::smartScript), new Fakes.FakeSteam(), List.of(), d);
            eq(plan.sessions().size(), again.currentCalendar().orElseThrow().sessions().size(), "plan survives a restart");
            eq(3, again.availability().size(), "availability survives a restart");
            eq(2, again.calendarGames().size(), "added games survive a restart");
        });
        test("calendar: the two strategies differ - focus finishes games in turn, mix rotates", () -> {
            Path d = tmp();
            AgentController c = controller(new Fakes.ScriptedLLM(Fakes::smartScript), new Fakes.FakeSteam(), List.of(), d);
            c.setAvailability("daily 18:00-21:00");
            c.addCalendarGame("Celeste", 6.0);
            c.addCalendarGame("Hades", 6.0);
            var focus = c.createCalendar(1, gameagent.calendar.ScheduleMode.FOCUS);
            eq("Celeste", focus.sessions().get(0).game(), "first game first");
            eq("Celeste", focus.sessions().get(1).game(), "focus stays on the first game");
            var mix = c.createCalendar(1, gameagent.calendar.ScheduleMode.MIX);
            eq("Celeste", mix.sessions().get(0).game(), "mix starts the same");
            eq("Hades", mix.sessions().get(1).game(), "mix rotates to the next game");
            for (var s : mix.sessions()) check(s.minutes() <= 90, "mix keeps sessions short: " + s.minutes());
            check(focus.progress().stream().allMatch(gameagent.calendar.GameProgress::finished), "12 h fits in 21 h of free time");
        });
        test("calendar: limited free time warns and offers shorter games, splitting and a changed schedule", () -> {
            AgentController c = controller(new Fakes.ScriptedLLM(Fakes::smartScript), new Fakes.FakeSteam(), List.of(), tmp());
            c.setAvailability("mon 20:00-21:00");
            c.addCalendarGame("Hollow Knight", 40.0);
            c.addCalendarGame("Celeste", 8.0);
            var plan = c.createCalendar(1, gameagent.calendar.ScheduleMode.FOCUS);
            check(plan.progress().stream().anyMatch(p -> !p.finished()), "something does not fit");
            check(plan.warnings().stream().anyMatch(w -> w.contains("Not everything fits")), "warned: " + plan.warnings());
            String all = String.join(" | ", plan.suggestions());
            check(all.contains("shorter games") && all.contains("Celeste"), "suggests short games: " + all);
            check(all.contains("Split games into sessions") && all.contains("mix"), "suggests splitting: " + all);
            check(all.contains("Change the schedule"), "suggests changing the schedule: " + all);
            int scheduled = plan.progress().stream().mapToInt(gameagent.calendar.GameProgress::scheduledMinutes).sum();
            eq(plan.totalMinutes(), scheduled, "progress adds up to what was scheduled");
            check(plan.totalMinutes() <= 60, "never schedules beyond the free time: " + plan.totalMinutes());
        });
        test("calendar: refinement blocks days, prioritises and excludes games, and keeps the old plan on failure", () -> {
            AgentController c = controller(new Fakes.ScriptedLLM(Fakes::smartScript), new Fakes.FakeSteam(), List.of(), tmp());
            try { c.refineCalendar("no games on Friday"); check(false, "needs a plan"); } catch (gameagent.calendar.CalendarException e) { check(e.getMessage().contains("no calendar yet"), e.getMessage()); }
            c.setAvailability("fri 19:00-23:00; sat 10:00-14:00");
            c.addCalendarGame("Celeste", 4.0);
            c.addCalendarGame("Hades", 4.0);
            c.addCalendarGame("Dead Cells", 4.0);
            var before = c.createCalendar(2, gameagent.calendar.ScheduleMode.FOCUS);
            check(before.sessions().stream().anyMatch(s -> s.date().getDayOfWeek() == java.time.DayOfWeek.FRIDAY), "friday used at first");
            var after = c.refineCalendar("no games on Friday");
            check(after.sessions().stream().noneMatch(s -> s.date().getDayOfWeek() == java.time.DayOfWeek.FRIDAY), "friday blocked");
            var third = c.refineCalendar("play Hades first, skip dead cells, shorter sessions");
            eq("Hades", third.sessions().get(0).game(), "priority honoured");
            check(third.sessions().stream().noneMatch(s -> s.game().equals("Dead Cells")), "excluded game left out");
            check(third.sessions().stream().allMatch(s -> s.minutes() <= 60), "session cap honoured");
            eq(gameagent.calendar.ScheduleMode.MIX, third.mode(), "mode switched");
            check(third.sessions().stream().noneMatch(s -> s.date().getDayOfWeek() == java.time.DayOfWeek.FRIDAY), "earlier adjustments are kept");
            c.setAvailability("fri 19:00-23:00");
            try { c.refineCalendar("no games on Friday"); check(false, "all days blocked"); }
            catch (gameagent.calendar.CalendarException e) { check(e.getMessage().contains("blocked"), e.getMessage()); }
            eq(third.sessions().size(), c.currentCalendar().orElseThrow().sessions().size(), "failed refinement leaves the plan alone");
        });
        test("calendar: AI outages never break scheduling when hours are known", () -> {
            Fakes.ScriptedLLM llm = new Fakes.ScriptedLLM(Fakes::smartScript);
            AgentController c = controller(llm, new Fakes.FakeSteam(), List.of(), tmp());
            c.setAvailability("sat 10:00-14:00");
            c.addCalendarGame("Celeste", 3.0);
            c.addCalendarGame("Mystery Game", null);
            llm.down = true;
            var plan = c.createCalendar(1, gameagent.calendar.ScheduleMode.FOCUS);
            check(plan.warnings().stream().anyMatch(w -> w.contains("Couldn't estimate")), "says estimates failed: " + plan.warnings());
            eq(180, plan.progress().get(0).totalMinutes(), "user hours unaffected");
            eq(600, plan.progress().get(1).totalMinutes(), "neutral default for the unknown game");
            try { c.refineCalendar("no games on Friday"); check(false, "refine needs the AI"); }
            catch (gameagent.calendar.CalendarException e) { check(e.getMessage().contains("AI"), e.getMessage()); }
        });
        test("calendar: profile changes mark the plan out of date (Observer)", () -> {
            AgentController c = controller(new Fakes.ScriptedLLM(Fakes::smartScript), new Fakes.FakeSteam(), List.of(), tmp());
            c.setAvailability("sat 10:00-14:00");
            c.addCalendarGame("Celeste", 3.0);
            c.createCalendar(1, gameagent.calendar.ScheduleMode.FOCUS);
            check(!c.calendarIsStale(), "fresh");
            c.updateProfile(new ProfileUpdate("user").like(Category.GENRE, "Puzzle"), ConflictResolver.NEWEST_WINS);
            check(c.calendarIsStale(), "flagged after a profile change");
            c.createCalendar(1, gameagent.calendar.ScheduleMode.FOCUS);
            check(!c.calendarIsStale(), "rebuilt plan is fresh");
        });
        test("calendar: saved recommendations are scheduled too, and the chat tool builds a calendar", () -> {
            AgentController c = controller(new Fakes.ScriptedLLM(Fakes::smartScript), new Fakes.FakeSteam(), List.of(), tmp());
            c.recommend("something like Hades", 3);
            c.setAvailability("daily 19:00-22:00");
            var plan = c.createCalendar(1, gameagent.calendar.ScheduleMode.MIX);
            check(plan.progress().stream().anyMatch(p -> p.name().equals("Dead Cells")), "recommended game is planned: " + plan.progress());
            var tool = new gameagent.agent.tools.PlanCalendarTool(null);
            check(tool.name().equals("plan_calendar") && tool.description().contains("weeks"), "tool describes itself");
        });
        test("cli: /calendar end to end", () -> {
            AgentController c = controller(new Fakes.ScriptedLLM(Fakes::smartScript), new Fakes.FakeSteam(), List.of(), tmp());
            String input = "/calendar\n/calendar when nonsense\n/calendar when mon,wed 20:00-22:00 chill; sat 14:00-16:00\n/calendar make\n"
                    + "/calendar add Celeste | 8\n/calendar add Hades | lots\n/calendar games\n/calendar make month mix\n/calendar tweak no games on Friday\n"
                    + "/calendar remove Celeste\n/calendar\n/calendar reset\n/quit\n";
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            new CliApp(c, new BufferedReader(new StringReader(input)), new PrintStream(buf, true, StandardCharsets.UTF_8)).run();
            String out = buf.toString(StandardCharsets.UTF_8);
            System.out.println("    ---- CLI transcript (excerpt) ----");
            for (String line : out.split("\n")) System.out.println("    | " + line);
            for (String must : new String[]{"No availability set", "Saved: Mon 20:00-22:00 (chill)",
                    "There are no games to schedule yet", "Added Celeste (8.0 h).", "isn't a number of hours", "Gaming calendar:", "Progress:",
                    "Celeste: ", "mix mode", "Removed Celeste.", "Adjustments cleared"}) {
                check(out.contains(must), "CLI output should contain: " + must);
            }
        });

        System.out.println("Playtime estimator");
        test("playtime: pace strategy scales by the median ratio, limited to a sensible range", () -> {
            var general = new gameagent.playtime.GameEstimate(20, List.of(), true);
            var slow = List.of(new gameagent.playtime.PaceSample("A", 10, 15), new gameagent.playtime.PaceSample("B", 10, 14), new gameagent.playtime.PaceSample("C", 10, 30));
            var r = new gameagent.playtime.PacePlaytimeStrategy().estimate("X", general, slow, 0);
            check(r.personalized() && r.samples() == 3, "personalised from 3 samples");
            eq(30.0, r.personalHours(), "median 1.5 x 20 h (the outlier of 3.0 is capped and does not swing it)");
            var extreme = List.of(new gameagent.playtime.PaceSample("A", 10, 1), new gameagent.playtime.PaceSample("B", 10, 1), new gameagent.playtime.PaceSample("C", 10, 1));
            eq(12.0, new gameagent.playtime.PacePlaytimeStrategy().estimate("X", general, extreme, 0).personalHours(), "never below 60%");
            check(r.notes().get(0).contains("slower than"), r.notes().get(0));
        });
        test("playtime: uses your pace with enough history, otherwise says the general figure is all it has", () -> {
            Map<String, Double> typical = Map.of("Target", 20.0, "A", 10.0, "B", 10.0, "C", 10.0, "Replayed", 10.0, "Dropped", 40.0);
            gameagent.playtime.PlaytimeEstimator est = games -> {
                Map<String, gameagent.playtime.GameEstimate> m = new java.util.LinkedHashMap<>();
                for (String g : games) m.put(g, typical.containsKey(g) ? new gameagent.playtime.GameEstimate(typical.get(g), List.of(), true) : gameagent.playtime.GameEstimate.unknown());
                return m;
            };
            gameagent.profile.ProfileManager pm = new gameagent.profile.ProfileManager(new gameagent.profile.UserProfile());
            var svc = new gameagent.playtime.PlaytimeService(gameagent.playtime.GameLengthSource.none(), est, pm);
            var link = new gameagent.model.SteamLink("76561198000000001", "P", "https://steamcommunity.com/profiles/76561198000000001", java.time.Instant.now());
            var none = svc.estimate("Target");
            check(!none.personalized() && none.notes().stream().anyMatch(n -> n.contains("no play history")), "no history: " + none.notes());
            eq(20.0, none.personalHours(), "general figure");
            pm.linkSteam(link, List.of(new gameagent.model.OwnedGame(1, "A", 15, 0), new gameagent.model.OwnedGame(2, "Replayed", 200, 0),
                    new gameagent.model.OwnedGame(3, "Dropped", 2, 0), new gameagent.model.OwnedGame(4, "Unknown Thing", 30, 0)));
            var few = svc.estimate("Target");
            check(!few.personalized() && few.notes().stream().anyMatch(n -> n.contains("only found 1")), "replays, abandoned and unknown games are not evidence: " + few.notes());
            pm.linkSteam(link, List.of(new gameagent.model.OwnedGame(1, "A", 15, 0), new gameagent.model.OwnedGame(5, "B", 15, 0),
                    new gameagent.model.OwnedGame(6, "C", 15, 0), new gameagent.model.OwnedGame(7, "Target", 3, 0)));
            var r = svc.estimate("Target");
            check(r.personalized() && r.strategy().equals("pace"), "personalised: " + r.notes());
            eq(30.0, r.personalHours(), "1.5 x 20");
            eq(3.0, r.loggedHours(), "hours already in the game");
            check(r.notes().stream().anyMatch(n -> n.contains("already logged 3 h")), "mentions logged hours: " + r.notes());
            check(r.notes().stream().anyMatch(n -> n.contains("IGDB keys")), "tells how to get real data: " + r.notes());
            try { svc.estimate("Nonexistent Title"); check(false, "unknown game"); }
            catch (gameagent.playtime.PlaytimeException e) { check(e.getMessage().contains("reliable playtime data"), e.getMessage()); }
            try { svc.estimate("  "); check(false, "blank"); } catch (gameagent.playtime.PlaytimeException expected) { }
        });
        test("cli: /playtime and the chat tool", () -> {
            Fakes.ScriptedLLM llm = new Fakes.ScriptedLLM(Fakes::smartScript);
            AgentController c = controller(llm, new Fakes.FakeSteam(), List.of(), tmp());
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            new CliApp(c, new BufferedReader(new StringReader("/playtime\n/playtime Hades\n/steam hadesfan\n/playtime Hades\n/quit\n")), new PrintStream(buf, true, StandardCharsets.UTF_8)).run();
            String out = buf.toString(StandardCharsets.UTF_8);
            System.out.println("    ---- CLI transcript (excerpt) ----");
            for (String line : out.split("\n")) System.out.println("    | " + line);
            for (String must : new String[]{"Which game?", "Typical time to beat: about 22 h", "no play history", "already logged 213 h"}) {
                check(out.contains(must), "CLI output should contain: " + must);
            }
            var tool = new gameagent.agent.tools.EstimatePlaytimeTool(null);
            check(tool.name().equals("estimate_playtime"), "tool name");
        });

        test("igdb: authenticates, matches the exact title, converts seconds to hours, never leaks the secret", () -> {
            List<String> seen = new ArrayList<>();
            gameagent.util.HttpPoster fake = (url, headers, body) -> {
                seen.add(url + " | " + headers.getOrDefault("Authorization", "") + " | " + headers.getOrDefault("Client-ID", "") + " | " + body);
                if (url.contains("id.twitch.tv")) return "{\"access_token\":\"tok123\",\"expires_in\":5000000,\"token_type\":\"bearer\"}";
                if (url.endsWith("/games")) {
                    if (body.contains("Nothing Known")) return "[]";
                    return "[{\"id\":9,\"name\":\"Hades II\"},{\"id\":113112,\"name\":\"Hades\"}]";
                }
                if (url.endsWith("/game_time_to_beats")) {
                    if (body.contains("game_id")) throw new gameagent.util.HttpPoster.HttpStatusException(400, "api.igdb.com");
                    return "[{\"id\":1,\"hastily\":57600,\"normally\":79200,\"completely\":187200,\"count\":321}]";
                }
                throw new java.io.IOException("unexpected " + url);
            };
            var src = new gameagent.playtime.IgdbGameLengthSource(fake, "my-client", "S3CRET");
            var len = src.lookup("Hades").orElseThrow();
            eq("Hades", len.matchedName(), "exact match, not the sequel");
            eq(22.0, len.normalHours(), "79200 s = 22 h");
            eq(16.0, len.hastyHours(), "hasty");
            eq(52.0, len.completeHours(), "completionist");
            eq(321, len.submissions(), "count");
            check(seen.stream().anyMatch(s -> s.contains("Bearer tok123") && s.contains("my-client")), "bearer token and client id sent: " + seen);
            check(seen.stream().anyMatch(s -> s.contains("game = 113112")), "falls back to the alternate field name on HTTP 400: " + seen);
            check(src.lookup("Nothing Known").isEmpty(), "unknown game is empty, not an error");
            long tokenCalls = seen.stream().filter(s -> s.contains("id.twitch.tv")).count();
            eq(1L, tokenCalls, "the token is cached");
            gameagent.util.HttpPoster denied = (url, headers, body) -> { throw new gameagent.util.HttpPoster.HttpStatusException(403, "id.twitch.tv"); };
            try { new gameagent.playtime.IgdbGameLengthSource(denied, "my-client", "S3CRET").lookup("Hades"); check(false, "should fail"); }
            catch (gameagent.playtime.PlaytimeException e) { check(e.getMessage().contains("IGDB_CLIENT_SECRET") && !e.getMessage().contains("S3CRET"), "helpful and secret-free: " + e.getMessage()); }
            gameagent.util.HttpPoster none = (url, headers, body) -> url.contains("twitch") ? "{\"access_token\":\"t\",\"expires_in\":9999}" : "[]";
            check(new gameagent.playtime.IgdbGameLengthSource(none, "a", "b").lookup("  ").isEmpty(), "blank name");
        });
        test("playtime: third-party figures win over the AI, the AI covers gaps, outages are explained", () -> {
            gameagent.playtime.PlaytimeEstimator ai = games -> {
                Map<String, gameagent.playtime.GameEstimate> m = new java.util.LinkedHashMap<>();
                for (String g : games) m.put(g, new gameagent.playtime.GameEstimate(99, List.of(), true));
                return m;
            };
            boolean[] fail = {false};
            gameagent.playtime.GameLengthSource db = new gameagent.playtime.GameLengthSource() {
                public String name() { return "IGDB"; }
                public java.util.Optional<gameagent.playtime.GameLength> lookup(String g) throws gameagent.playtime.PlaytimeException {
                    if (fail[0]) throw new gameagent.playtime.PlaytimeException("HTTP 429 from api.igdb.com");
                    return g.equals("Hades") ? java.util.Optional.of(new gameagent.playtime.GameLength("Hades", 16, 22, 52, 321, "IGDB")) : java.util.Optional.empty();
                }
            };
            var pm = new gameagent.profile.ProfileManager(new gameagent.profile.UserProfile());
            var svc = new gameagent.playtime.PlaytimeService(db, ai, pm);
            var r = svc.estimate("Hades");
            eq(22.0, r.generalHours(), "IGDB normal time, not the AI's 99");
            eq("IGDB", r.source(), "source");
            eq(16.0, r.lowHours(), "low"); eq(52.0, r.highHours(), "high"); eq(321, r.submissions(), "players");
            check(r.notes().get(0).contains("IGDB (321 players)") && r.notes().get(0).contains("rushed"), r.notes().get(0));
            var gap = svc.estimate("Obscure Game");
            eq(99.0, gap.generalHours(), "AI covers what the database lacks");
            eq("AI estimate", gap.source(), "source says AI");
            check(gap.notes().get(0).contains("IGDB had no data"), gap.notes().get(0));
            fail[0] = true;
            var down = new gameagent.playtime.PlaytimeService(db, ai, pm).estimate("Hades");
            eq(99.0, down.generalHours(), "outage falls back to the AI");
            check(down.notes().stream().anyMatch(n -> n.contains("IGDB is unavailable") && n.contains("429")), "outage explained: " + down.notes());
        });
        test("config: IGDB keys are read from .env and placeholders count as unset", () -> {
            check(new AppConfig(Map.of()).igdbClientId().isEmpty(), "unset");
            check(new AppConfig(Map.of("IGDB_CLIENT_ID", "YOUR_ID", "IGDB_CLIENT_SECRET", "abc")).igdbClientId().isEmpty(), "placeholder is unset");
            eq("abc", new AppConfig(Map.of("IGDB_CLIENT_SECRET", "abc")).igdbClientSecret(), "secret read");
        });

        System.out.println("Friend recommendations");
        test("friends: add by profile link, list, persist, reject yourself and private profiles", () -> {
            Path d = tmp();
            AgentController c = controller(new Fakes.ScriptedLLM(Fakes::smartScript), new Fakes.FakeSteam(), List.of(), d);
            var alex = c.addFriend("https://steamcommunity.com/id/alexfriend/");
            eq("Alex", alex.name(), "persona name");
            eq(3, alex.games().size(), "tools such as Wallpaper Engine are dropped");
            c.addFriend("samfriend");
            eq(2, c.friends().size(), "two friends");
            for (String bad : new String[]{"https://steamcommunity.com/id/privateguy/", "https://steamcommunity.com/id/hiddengames/"}) {
                try { c.addFriend(bad); check(false, "private must fail: " + bad); }
                catch (gameagent.steam.SteamProfileException e) { check(e.getMessage().contains("different friend") || e.getMessage().contains("public"), e.getMessage()); }
            }
            try { c.addFriend("https://steamcommunity.com/id/nobody/"); check(false, "unknown"); } catch (gameagent.steam.SteamProfileException e) { check(e.getMessage().length() > 5, e.getMessage()); }
            c.linkSteam("hadesfan");
            try { c.addFriend("hadesfan"); check(false, "yourself"); } catch (gameagent.steam.SteamProfileException e) { check(e.getMessage().contains("your own"), e.getMessage()); }
            eq(2, c.friends().size(), "failed adds change nothing");
            AgentController again = controller(new Fakes.ScriptedLLM(Fakes::smartScript), new Fakes.FakeSteam(), List.of(), d);
            eq(2, again.friends().size(), "friends survive a restart");
            check(again.removeFriend("sam") && again.friends().size() == 1, "remove by name prefix");
            check(!again.removeFriend("nobody here"), "unknown removal");
        });
        test("friends: recommendations skip what you own, count several friends higher, and name the friends", () -> {
            Fakes.ScriptedLLM llm = new Fakes.ScriptedLLM(Fakes::smartScript);
            AgentController c = controller(llm, new Fakes.FakeSteam(), List.of(), tmp());
            try { c.recommendFromFriends("", 5); check(false, "no friends"); } catch (gameagent.recommend.RecommendationException e) { check(e.getMessage().contains("/friends add"), e.getMessage()); }
            c.linkSteam("hadesfan"); // owns Hollow Knight, Counter-Strike 2, Dead Cells...
            c.addFriend("alexfriend");
            c.addFriend("samfriend");
            var r = c.recommendFromFriends("", 5);
            var names = r.recommendations().stream().map(x -> x.game().name()).toList();
            check(names.contains("Risk of Rain 2") && names.contains("Rain World"), "friends' new games: " + names);
            check(names.stream().noneMatch(n -> List.of("Hollow Knight", "Counter-Strike 2", "Dead Cells", "Wallpaper Engine", "Invented Friend Game").contains(n)),
                    "owned, tool and invented games are excluded: " + names);
            eq("Risk of Rain 2", names.get(0), "played by two friends and ranked first");
            var top = r.recommendations().get(0);
            check(top.label().contains("Alex") && top.label().contains("Sam"), "label names the friends: " + top.label());
            check(top.game().verified() && !top.game().genres().isEmpty(), "enriched with store data");
            check(r.warnings().stream().anyMatch(w -> w.contains("most-played")), "limit disclosed: " + r.warnings());
            AgentController other = controller(new Fakes.ScriptedLLM(Fakes::smartScript), new Fakes.FakeSteam(), List.of(), tmp());
            other.linkSteam("hadesfan");
            other.addFriend("alexfriend");
            other.addFriend("samfriend");
            var onlySam = other.recommendFromFriends("sam", 5);
            check(onlySam.recommendations().stream().noneMatch(x -> x.game().name().equals("Rain World")), "Rain World is only Alex's");
            check(onlySam.recommendations().stream().allMatch(x -> x.label().equals("Friends: Sam")), "only Sam credited");
            try { c.recommendFromFriends("nobody", 5); check(false, "unknown friend"); } catch (gameagent.recommend.RecommendationException e) { check(e.getMessage().contains("don't have a friend"), e.getMessage()); }
            check(c.catalog().all().stream().anyMatch(x -> x.game().name().equals("Risk of Rain 2")), "saved to the catalogue");
        });
        test("friends: works without the AI, ranks by friend playtime and taste, and disliked genres sink a game", () -> {
            Fakes.ScriptedLLM llm = new Fakes.ScriptedLLM(Fakes::smartScript);
            AgentController c = controller(llm, new Fakes.FakeSteam(), List.of(), tmp());
            c.addFriend("alexfriend");
            c.updateProfile(new ProfileUpdate("user").dislike(Category.GENRE, "Adventure"), ConflictResolver.NEWEST_WINS);
            llm.down = true;
            var r = c.recommendFromFriends("", 5);
            check(r.warnings().stream().anyMatch(w -> w.contains("AI is unavailable")), "explained: " + r.warnings());
            eq("Risk of Rain 2", r.recommendations().get(0).game().name(), "Rain World (Adventure) is disliked, so it ranks lower");
            check(r.recommendations().get(0).reason().contains("Alex has 85 h"), r.recommendations().get(0).reason());
            var game = new gameagent.model.Game("X", 1, "", List.of("Action", "Adventure"), "", "", "", true);
            var likes = new gameagent.profile.UserProfile();
            double neutral = gameagent.recommend.FriendsStrategy.tasteScore(game, likes);
            eq(0.0, neutral, "no tastes, no effect");
        });
        test("friends: common games and refresh", () -> {
            AgentController c = controller(new Fakes.ScriptedLLM(Fakes::smartScript), new Fakes.FakeSteam(), List.of(), tmp());
            c.linkSteam("hadesfan");
            c.addFriend("alexfriend");
            var common = c.commonGames("alex");
            check(common.shared().contains("Hollow Knight"), "shared: " + common.shared());
            check(common.onlyTheirs().stream().anyMatch(g -> g.name().equals("Rain World")), "theirs only");
            try { c.commonGames("stranger"); check(false, "unknown"); } catch (gameagent.friends.FriendException e) { check(e.getMessage().contains("don't have a friend"), e.getMessage()); }
            check(c.refreshFriends().isEmpty(), "refresh is clean");
        });
        test("cli: /friends end to end", () -> {
            AgentController c = controller(new Fakes.ScriptedLLM(Fakes::smartScript), new Fakes.FakeSteam(), List.of(), tmp());
            String input = "/friends\n/friends add https://steamcommunity.com/id/privateguy/\n/friends add https://steamcommunity.com/id/alexfriend/\n"
                    + "/friends https://steamcommunity.com/id/samfriend/\n/friends\n/friends recommend\n/friends recommend alex 1\n/friends common alex\n"
                    + "/friends remove sam\n/friends refresh\n/quit\n";
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            new CliApp(c, new BufferedReader(new StringReader(input)), new PrintStream(buf, true, StandardCharsets.UTF_8)).run();
            String out = buf.toString(StandardCharsets.UTF_8);
            System.out.println("    ---- CLI transcript (excerpt) ----");
            for (String line : out.split("\n")) System.out.println("    | " + line);
            for (String must : new String[]{"No friends added yet", "pick a different friend", "Added Alex", "Added Sam", "Risk of Rain 2", "Friends: Alex, Sam",
                    "Removed sam.", "Friends refreshed."}) {
                check(out.contains(must), "CLI output should contain: " + must);
            }
        });

        PatternGuards.run();

        System.out.println("\n" + passed + " passed, " + failed + " failed");
        failures.forEach(f -> System.out.println("FAILED: " + f));
        System.exit(failed == 0 ? 0 : 1);
    }
}
