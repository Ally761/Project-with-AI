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
import gameagent.agent.tools.GetNewsTool;
import gameagent.agent.tools.GetProfileTool;
import gameagent.agent.tools.RecommendGamesTool;
import gameagent.agent.tools.SearchGamesTool;
import gameagent.agent.tools.UpdatePreferencesTool;
import gameagent.config.AppConfig;
import gameagent.data.GameDataSource;
import gameagent.data.SteamStoreAdapter;
import gameagent.llm.LLMClient;
import gameagent.llm.LLMClientFactory;
import gameagent.news.NewsService;
import gameagent.news.NewsSource;
import gameagent.news.RssNewsSource;
import gameagent.profile.AutoSaveListener;
import gameagent.profile.JsonProfileRepository;
import gameagent.profile.ProfileManager;
import gameagent.profile.ProfileRepository;
import gameagent.recommend.GameCatalog;
import gameagent.recommend.RecommendationFactory;
import gameagent.recommend.RecommendationService;
import gameagent.util.JdkHttpFetcher;

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
        return build(LLMClientFactory.create(cfg, notifier), new SteamStoreAdapter(http, cfg.steamCountry()), sources, cfg.dataDir());
    }

    public static AgentController build(LLMClient llm, GameDataSource data, List<NewsSource> newsSources, Path dataDir) {
        ProfileRepository repo = new JsonProfileRepository(dataDir.resolve("profile.json"));
        ProfileManager profiles = new ProfileManager(repo.load());
        GameCatalog catalog = new GameCatalog(dataDir.resolve("catalog.json"));
        NewsService news = new NewsService(newsSources, llm, profiles, dataDir.resolve("news-cache.json"));

        // Observers: persistence, catalogue staleness, news ranking cache
        profiles.addListener(new AutoSaveListener(profiles, repo));
        profiles.addListener(catalog);
        profiles.addListener(news);

        RecommendationService recs = new RecommendationService(new RecommendationFactory(llm, data, profiles), catalog);
        ToolRegistry tools = new ToolRegistry()
                .register(new UpdatePreferencesTool(profiles))
                .register(new RecommendGamesTool(recs))
                .register(new SearchGamesTool(data))
                .register(new GetNewsTool(news))
                .register(new GetProfileTool(profiles));
        ConversationMemory memory = new ConversationMemory(12);
        ChatAgent chat = new ChatAgent(llm, tools, profiles, memory);

        return new AgentController(llm, profiles, chat, memory, recs, news,
                new FeedbackService(profiles, llm, data), new RefinementService(profiles, llm), data);
    }
}
