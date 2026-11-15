package gameagent;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import gameagent.agent.AgentController;
import gameagent.data.SteamStoreAdapter;
import gameagent.llm.FallbackLLMClient;
import gameagent.llm.LLMClient;
import gameagent.llm.LLMRequest;
import gameagent.llm.ResilientLLMClient;
import gameagent.model.GameFeedback;
import gameagent.model.OwnedGame;
import gameagent.model.SteamLink;
import gameagent.profile.Category;
import gameagent.profile.ConflictResolver;
import gameagent.profile.JsonProfileRepository;
import gameagent.profile.ProfileEvent;
import gameagent.profile.ProfileManager;
import gameagent.profile.ProfileUpdate;
import gameagent.profile.UserProfile;
import gameagent.recommend.RecommendationFactory;
import gameagent.recommend.RecommendationStrategy;

/**
 * Guards for the architecture. Each test pins down one design pattern so that a later feature cannot quietly break it:
 * if the structure the pattern depends on is bypassed, the suite fails and says which rule was broken.
 * Source-level rules read src/main/java (the suite is run from the project folder by test.bat / test.sh).
 */
final class PatternGuards {
    private PatternGuards() {}

    private static final Pattern IMPORT = Pattern.compile("(?m)^import (gameagent\\.[\\w.]+);");

    /** Classes that wrap third-party services or the network. Only the composition roots may name them. */
    private static final List<String> CONCRETE = List.of("SteamStoreAdapter", "SteamCommunityAdapter", "RssNewsSource",
            "GeminiClient", "OpenAICompatibleClient", "JdkHttpFetcher");

    static void run() throws Exception {
        System.out.println("Design pattern guards");
        Map<String, String> src = sources();
        if (src.isEmpty()) {
            AllTests.test("pattern guards: source folder found (run the suite from the project folder)", () -> {
                throw new AssertionError("src/main/java not found from " + Path.of("").toAbsolutePath());
            });
            return;
        }

        AllTests.test("Facade: the CLI reaches the application only through AgentController", () -> {
            // Result/parameter types that are nested inside service classes. Known coupling: the CLI names them but never calls the service.
            Set<String> nestedResultTypes = Set.of("gameagent.profile.ProfileManager", "gameagent.agent.FeedbackService",
                    "gameagent.agent.RefinementService", "gameagent.steam.SteamLinkService");
            Set<String> readModels = Set.of("gameagent.agent.AgentController", "gameagent.agent.AgentReply",
                    "gameagent.profile.Category", "gameagent.profile.ConflictResolver", "gameagent.profile.Preference",
                    "gameagent.profile.ProfileUpdate", "gameagent.profile.Sentiment", "gameagent.profile.UserProfile",
                    "gameagent.llm.LLMException", "gameagent.news.NewsException", "gameagent.news.NewsResult",
                    "gameagent.recommend.RecommendationException", "gameagent.recommend.RecommendationResult",
                    "gameagent.steam.SteamProfileException");
            List<String> violations = new ArrayList<>();
            for (var e : src.entrySet()) {
                if (!e.getKey().startsWith("gameagent/cli/")) continue;
                Matcher m = IMPORT.matcher(e.getValue());
                while (m.find()) {
                    String imp = m.group(1);
                    boolean ok = imp.startsWith("gameagent.cli.") || imp.startsWith("gameagent.model.")
                            || readModels.contains(imp) || nestedResultTypes.contains(imp);
                    if (!ok) violations.add(e.getKey() + " imports " + imp);
                }
            }
            AllTests.check(violations.isEmpty(), "CLI must go through AgentController (Facade). Bypassed by: " + violations);
            AllTests.check(src.get("gameagent/agent/AgentController.java").contains("public SteamLinkService.LinkResult linkSteam"),
                    "Steam is exposed on the facade");
        });

        AllTests.test("Adapter + dependency inversion: only the composition roots name concrete Steam/RSS/LLM/HTTP classes", () -> {
            List<String> violations = new ArrayList<>();
            for (var e : src.entrySet()) {
                String file = e.getKey().substring(e.getKey().lastIndexOf('/') + 1).replace(".java", "");
                if (file.equals("AppContext") || file.equals("LLMClientFactory") || CONCRETE.contains(file)) continue;
                for (String c : CONCRETE) {
                    if (Pattern.compile("\\b" + c + "\\b").matcher(e.getValue()).find()) violations.add(e.getKey() + " uses " + c);
                }
            }
            AllTests.check(violations.isEmpty(), "Depend on the ports (GameDataSource, SteamProfileSource, NewsSource, LLMClient, HttpFetcher): " + violations);
            for (String port : List.of("data/GameDataSource", "steam/SteamProfileSource", "news/NewsSource", "llm/LLMClient", "util/HttpFetcher")) {
                AllTests.check(src.get("gameagent/" + port + ".java").contains("interface"), port + " is an interface");
            }
        });

        AllTests.test("Observer: every kind of profile change notifies listeners, and ProfileManager knows no listener class", () -> {
            AllTests.check(!src.get("gameagent/profile/ProfileManager.java").matches("(?s).*\\b(GameCatalog|NewsService|AutoSaveListener)\\b.*"),
                    "the subject must depend only on the ProfileListener interface");
            ProfileManager pm = new ProfileManager(new UserProfile());
            List<ProfileEvent.Type> seen = new ArrayList<>();
            pm.addListener(ev -> seen.add(ev.type()));
            pm.apply(new ProfileUpdate("user").like(Category.GENRE, "Roguelike"), ConflictResolver.NEWEST_WINS);
            pm.recordFeedback(new GameFeedback("Hades", 5, "", Instant.now()));
            pm.linkSteam(new SteamLink("76561198000000001", "P", "https://steamcommunity.com/profiles/76561198000000001", Instant.now()),
                    List.of(new OwnedGame(1, "A", 5, 0)));
            pm.unlinkSteam(false);
            pm.reset();
            for (ProfileEvent.Type t : List.of(ProfileEvent.Type.PREFERENCES_CHANGED, ProfileEvent.Type.FEEDBACK_ADDED,
                    ProfileEvent.Type.STEAM_CHANGED, ProfileEvent.Type.RESET)) {
                AllTests.check(seen.contains(t), "listeners are told about " + t + " (saw " + seen + ")");
            }
            AllTests.check(ProfileEvent.Type.STEAM_CHANGED.affectsRecommendations(), "a Steam change invalidates recommendations and news rankings");

            // The real listeners are wired: a Steam change alone must reach AutoSaveListener and GameCatalog.
            Path dir = AllTests.tmp();
            AgentController c = AllTests.controller(new Fakes.ScriptedLLM(Fakes::smartScript), new Fakes.FakeSteam(), List.of(), dir);
            c.linkSteam("hadesfan");
            AllTests.check(new JsonProfileRepository(dir.resolve("profile.json")).load().steamLink().isPresent(), "AutoSaveListener saved the link");
            c.unlinkSteam(false);
            AllTests.check(new JsonProfileRepository(dir.resolve("profile.json")).load().steamLink().isEmpty(), "AutoSaveListener saved the unlink");
            AllTests.check(c.catalog().isStale(), "GameCatalog was told the profile changed");
        });

        AllTests.test("Strategy + Factory: each recommendation type is a distinct RecommendationStrategy built by the factory", () -> {
            ProfileManager pm = new ProfileManager(new UserProfile());
            RecommendationFactory f = new RecommendationFactory(AllTests.stub("x", () -> "{}"), new SteamStoreAdapter(new Fakes.FakeSteam(), "US"), pm);
            Set<Class<?>> kinds = new HashSet<>();
            for (RecommendationFactory.Type t : RecommendationFactory.Type.values()) {
                RecommendationStrategy s = f.create(t);
                AllTests.check(s != null && !s.name().isBlank(), "strategy for " + t);
                kinds.add(s.getClass());
            }
            AllTests.check(kinds.size() == RecommendationFactory.Type.values().length && kinds.size() >= 2, "one distinct strategy per type: " + kinds);
            String service = src.get("gameagent/recommend/RecommendationService.java");
            AllTests.check(!service.contains("PersonalizedStrategy") && !service.contains("CurveballStrategy"),
                    "the context (RecommendationService) must get strategies from the factory, not construct them");
        });

        AllTests.test("Decorator: retry and fallback wrap any LLMClient, are LLMClients themselves, and stack", () -> {
            for (Class<?> c : List.of(ResilientLLMClient.class, FallbackLLMClient.class)) {
                AllTests.check(LLMClient.class.isAssignableFrom(c), c.getSimpleName() + " is an LLMClient");
                boolean wraps = false;
                for (Field fld : c.getDeclaredFields()) {
                    if (LLMClient.class.isAssignableFrom(fld.getType()) || java.util.List.class.isAssignableFrom(fld.getType())) wraps = true;
                }
                AllTests.check(wraps, c.getSimpleName() + " holds the client(s) it decorates");
            }
            LLMClient stacked = new ResilientLLMClient(new FallbackLLMClient(List.of(
                    new ResilientLLMClient(AllTests.stub("a", () -> { throw new gameagent.llm.LLMException("down", 503); }), 0, 0, 0),
                    AllTests.stub("b", () -> "from b")), null), 0);
            AllTests.eq("from b", stacked.complete(LLMRequest.json("s", "u")), "decorators compose in any order");
        });

        AllTests.test("Command: every command and every agent tool class is registered", () -> {
            String commands = src.get("gameagent/cli/commands/Commands.java");
            String context = src.get("gameagent/AppContext.java");
            List<String> missing = new ArrayList<>();
            for (var e : src.entrySet()) {
                String name = e.getKey().substring(e.getKey().lastIndexOf('/') + 1).replace(".java", "");
                if (e.getKey().startsWith("gameagent/cli/commands/") && name.endsWith("Command") && !name.equals("BaseCommand")
                        && !commands.contains("new " + name + "(")) missing.add(name + " not in Commands.standard()");
                if (e.getKey().startsWith("gameagent/agent/tools/") && name.endsWith("Tool") && !context.contains("new " + name + "("))
                    missing.add(name + " not registered in AppContext");
            }
            AllTests.check(missing.isEmpty(), "unregistered: " + missing);
            AllTests.check(gameagent.cli.commands.Commands.standard().find("steam").isPresent(), "/steam is a registered command");
        });
    }

    /** All main sources keyed like "gameagent/cli/CliApp.java", with comments removed so prose doesn't count as a dependency. */
    private static Map<String, String> sources() throws IOException {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.isDirectory(p.resolve("src/main/java/gameagent"))) p = p.getParent();
        Map<String, String> out = new TreeMap<>();
        if (p == null) return out;
        Path root = p.resolve("src/main/java");
        try (Stream<Path> files = Files.walk(root)) {
            for (Path f : (Iterable<Path>) files.filter(x -> x.toString().endsWith(".java"))::iterator) {
                String text = Files.readString(f);
                text = text.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?<!:)//[^\\n]*", "");
                out.put(root.relativize(f).toString().replace('\\', '/'), text);
            }
        }
        return out;
    }
}
