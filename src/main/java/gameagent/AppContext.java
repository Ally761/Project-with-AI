package gameagent;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import gameagent.agent.AgentController;
import gameagent.agent.ChatAgent;
import gameagent.agent.ConversationMemory;
import gameagent.agent.FeedbackService;
import gameagent.agent.RefinementService;
import gameagent.agent.ToolRegistry;
import gameagent.agent.tools.EstimatePlaytimeTool;
import gameagent.agent.tools.FriendsTool;
import gameagent.agent.tools.GetNewsTool;
import gameagent.agent.tools.PlanCalendarTool;
import gameagent.agent.tools.GetProfileTool;
import gameagent.agent.tools.LinkSteamTool;
import gameagent.agent.tools.RecommendGamesTool;
import gameagent.agent.tools.SearchGamesTool;
import gameagent.agent.tools.UpdatePreferencesTool;
import gameagent.calendar.CalendarService;
import gameagent.calendar.CalendarStore;
import gameagent.config.AppConfig;
import gameagent.data.GameDataSource;
import gameagent.friends.FriendService;
import gameagent.data.SteamStoreAdapter;
import gameagent.llm.LLMClient;
import gameagent.llm.LLMClientFactory;
import gameagent.news.NewsService;
import gameagent.playtime.GameLengthSource;
import gameagent.playtime.IgdbGameLengthSource;
import gameagent.playtime.LlmPlaytimeEstimator;
import gameagent.playtime.PlaytimeService;
import gameagent.news.NewsSource;
import gameagent.news.RssNewsSource;
import gameagent.profile.AutoSaveListener;
import gameagent.profile.JsonProfileRepository;
import gameagent.profile.ProfileManager;
import gameagent.profile.ProfileRepository;
import gameagent.recommend.GameCatalog;
import gameagent.recommend.CurveballRecommender;
import gameagent.recommend.FriendsRecommender;
import gameagent.recommend.PersonalizedRecommender;
import gameagent.recommend.RecommendationService;
import gameagent.steam.SteamCommunityAdapter;
import gameagent.steam.SteamLinkService;
import gameagent.steam.SteamProfileSource;
import gameagent.util.JdkHttpFetcher;
import gameagent.util.JdkHttpPoster;

/** Composition root: the only place that wires concrete classes together. Tests call {@link #build} with fakes. */
public final class AppContext {
    private AppContext() {}

    public static AgentController fromConfig(AppConfig cfg) {
        return fromConfig(cfg, null);
    }

    /** @param notifier receives retry/fallback status messages from the LLM layer (e.g. to print in the CLI) */
    public static AgentController fromConfig(AppConfig cfg, java.util.function.Consumer<String> notifier) {
        JdkHttpFetcher http = new JdkHttpFetcher();
        List<NewsSource> sources = new ArrayList<>();
        for (AppConfig.FeedSpec f : cfg.newsFeeds()) sources.add(new RssNewsSource(f.name(), f.url(), http));
        GameLengthSource lengths = cfg.igdbClientId().isEmpty() || cfg.igdbClientSecret().isEmpty() ? GameLengthSource.none()
                : new IgdbGameLengthSource(new JdkHttpPoster(), cfg.igdbClientId(), cfg.igdbClientSecret());
        return build(LLMClientFactory.create(cfg, notifier), new SteamStoreAdapter(http, cfg.steamCountry()),
                new SteamCommunityAdapter(http), sources, cfg.dataDir(), lengths);
    }

    public static AgentController build(LLMClient llm, GameDataSource data, SteamProfileSource steamProfiles,
                                        List<NewsSource> newsSources, Path dataDir) {
        return build(llm, data, steamProfiles, newsSources, dataDir, GameLengthSource.none());
    }

    public static AgentController build(LLMClient llm, GameDataSource data, SteamProfileSource steamProfiles,
                                        List<NewsSource> newsSources, Path dataDir, GameLengthSource lengths) {
        ProfileRepository repo = new JsonProfileRepository(dataDir.resolve("profile.json"));
        ProfileManager profiles = new ProfileManager(repo.load());
        GameCatalog catalog = new GameCatalog(dataDir.resolve("catalog.json"));
        NewsService news = new NewsService(newsSources, llm, profiles, dataDir.resolve("news-cache.json"));

        // Observers: persistence, catalogue staleness, news ranking cache
        profiles.addListener(new AutoSaveListener(profiles, repo));
        profiles.addListener(catalog);
        profiles.addListener(news);

        FriendService friends = new FriendService(steamProfiles, profiles, dataDir.resolve("friends.json"));
        RecommendationService recs = new RecommendationService(
                new PersonalizedRecommender(llm, data, profiles, catalog), new CurveballRecommender(llm, data, profiles, catalog),
                new FriendsRecommender(llm, data, profiles, friends, catalog), catalog);
        SteamLinkService steam = new SteamLinkService(steamProfiles, data, profiles);
        LlmPlaytimeEstimator estimator = new LlmPlaytimeEstimator(llm);
        PlaytimeService playtime = new PlaytimeService(lengths, estimator, profiles);
        CalendarService calendar = new CalendarService(new CalendarStore(dataDir.resolve("calendar.json")), catalog,
                estimator, llm, profiles, java.time.LocalDate::now);
        profiles.addListener(calendar);
        ToolRegistry tools = new ToolRegistry()
                .register(new UpdatePreferencesTool(profiles))
                .register(new RecommendGamesTool(recs))
                .register(new SearchGamesTool(data))
                .register(new GetNewsTool(news))
                .register(new GetProfileTool(profiles))
                .register(new LinkSteamTool(steam))
                .register(new PlanCalendarTool(calendar))
                .register(new EstimatePlaytimeTool(playtime))
                .register(new FriendsTool(friends, recs));
        ConversationMemory memory = new ConversationMemory(12);
        ChatAgent chat = new ChatAgent(llm, tools, profiles, memory);

        return new AgentController(llm, profiles, chat, memory, recs, news,
                new FeedbackService(profiles, llm, data), new RefinementService(profiles, llm), data, steam, calendar, playtime, friends);
    }
}
