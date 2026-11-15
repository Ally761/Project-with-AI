package gameagent;

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
        return AppContext.build(llm, new SteamStoreAdapter(steam, "US"), news, dir);
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
            FeedbackService.Result r = c.submitFeedback("Hades", 5, "great combat, weak story");
            eq(Sentiment.LIKE, c.profile().find(Category.GAME, "Hades").orElseThrow().sentiment(), "liked game");
            check(c.profile().find(Category.GENRE, "RPG").isPresent(), "genres from store data liked");
            check(c.profile().find(Category.TAG, "Weak story").map(p -> p.sentiment() == Sentiment.DISLIKE).orElse(false), "AI extracted dislike");
            FeedbackService.Result conflict = c.submitFeedback("Hades", 1, "");
            check(conflict.warnings().stream().anyMatch(w -> w.contains("conflicts")), "conflict warning");
            eq(1, c.profile().feedback().size(), "latest replaces earlier");
            eq(Sentiment.DISLIKE, c.profile().find(Category.GAME, "Hades").orElseThrow().sentiment(), "newest wins");
            try { c.submitFeedback("Hades", 9, ""); check(false, "throw"); } catch (IllegalArgumentException e) { check(true, ""); }
            llm.down = true;
            FeedbackService.Result degraded = c.submitFeedback("Dead Cells", 4, "fun");
            check(degraded.warnings().stream().anyMatch(w -> w.contains("could not be analysed")), "degrades gracefully");
            check(c.profile().find(Category.GAME, "Dead Cells").isPresent(), "rating still applied");
        });
        test("refinement: gaps -> questions -> answers -> profile updated; skip all = no AI call", () -> {
            Fakes.ScriptedLLM llm = new Fakes.ScriptedLLM(Fakes::smartScript);
            AgentController c = controller(llm, new Fakes.FakeSteam(), List.of(), tmp());
            List<String> qs = c.refinementQuestions(4);
            eq(3, qs.size(), "questions");
            int calls = llm.requests.size();
            c.submitRefinement(List.of(new RefinementService.QA(qs.get(0), " "), new RefinementService.QA(qs.get(1), "")));
            eq(calls, llm.requests.size(), "blank answers: no LLM call");
            var res = c.submitRefinement(List.of(new RefinementService.QA(qs.get(0), "PC, mostly"), new RefinementService.QA(qs.get(1), "about 45 minutes")));
            check(res.changedAnything(), "applied");
            check(c.profile().find(Category.PLATFORM, "PC").isPresent(), "platform stored");
            check(c.profile().notes().stream().anyMatch(n -> n.contains("45")), "note stored");
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
        test("cli: without API key the app still starts and profile commands work", () -> {
            AgentController c = AppContext.build(gameagent.llm.LLMClientFactory.create(new AppConfig(Map.of())), new SteamStoreAdapter(new Fakes.FakeSteam(), "US"), List.of(), tmp());
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            new CliApp(c, new BufferedReader(new StringReader("/like game Celeste\nhello there\n/recommend\n/profile\n/quit\n")), new PrintStream(buf, true, StandardCharsets.UTF_8)).run();
            String out = buf.toString(StandardCharsets.UTF_8);
            check(out.contains("No LLM API key"), "startup warning");
            check(out.contains("AI service problem: No LLM API key configured"), "chat error is friendly");
            check(out.contains("Celeste"), "profile works offline");
        });

        System.out.println("\n" + passed + " passed, " + failed + " failed");
        failures.forEach(f -> System.out.println("FAILED: " + f));
        System.exit(failed == 0 ? 0 : 1);
    }
}
