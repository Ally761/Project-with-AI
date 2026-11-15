package gameagent.agent;

import java.util.List;
import java.util.Optional;

import gameagent.data.DataSourceException;
import gameagent.data.GameDataSource;
import gameagent.llm.LLMClient;
import gameagent.llm.LLMException;
import gameagent.model.Game;
import gameagent.news.NewsException;
import gameagent.news.NewsResult;
import gameagent.news.NewsService;
import gameagent.profile.ConflictResolver;
import gameagent.profile.ProfileManager;
import gameagent.profile.ProfileUpdate;
import gameagent.profile.UserProfile;
import gameagent.recommend.GameCatalog;
import gameagent.recommend.RecommendationException;
import gameagent.recommend.RecommendationResult;
import gameagent.recommend.RecommendationService;

/**
 * Facade: the single entry point that user interfaces (CLI now, GUI later) talk to.
 * It hides the profile, LLM agent, recommendation, news, feedback and refinement subsystems.
 * UIs never touch those classes directly, so a GUI can reuse every method here unchanged.
 */
public class AgentController {
    private final LLMClient llm;
    private final ProfileManager profiles;
    private final ChatAgent chatAgent;
    private final ConversationMemory memory;
    private final RecommendationService recommendations;
    private final NewsService news;
    private final FeedbackService feedback;
    private final RefinementService refinement;
    private final GameDataSource data;

    public AgentController(LLMClient llm, ProfileManager profiles, ChatAgent chatAgent, ConversationMemory memory,
                           RecommendationService recommendations, NewsService news, FeedbackService feedback,
                           RefinementService refinement, GameDataSource data) {
        this.llm = llm;
        this.profiles = profiles;
        this.chatAgent = chatAgent;
        this.memory = memory;
        this.recommendations = recommendations;
        this.news = news;
        this.feedback = feedback;
        this.refinement = refinement;
        this.data = data;
    }

    public String modelName() { return llm.name(); }

    // ---- conversation
    public AgentReply chat(String text) throws LLMException { return chatAgent.chat(text); }

    // ---- recommendations / catalogue
    public RecommendationResult recommend(String query, int count) throws RecommendationException {
        profiles.log("recommend", query == null ? "" : query);
        return recommendations.personalized(query, count);
    }

    public GameCatalog catalog() { return recommendations.catalog(); }

    /** True when there is too little profile data for good recommendations (UI should suggest refinement). */
    public boolean needsMoreInfo() { return profiles.profile().isSparse(); }

    // ---- curveball
    public RecommendationResult curveball(String hint) throws RecommendationException {
        profiles.log("curveball", hint == null ? "" : hint);
        return recommendations.curveball(hint);
    }

    // ---- news
    public NewsResult news(String topic, int limit) throws NewsException { return news.getNews(topic, limit); }

    // ---- profile
    public UserProfile profile() { return profiles.profile(); }

    public ProfileManager.ApplyResult updateProfile(ProfileUpdate update, ConflictResolver resolver) {
        return profiles.apply(update, resolver);
    }

    public boolean removeNote(int index) { return profiles.removeNote(index); }

    public void resetProfile() {
        profiles.reset();
        recommendations.catalog().clear();
        memory.clear();
    }

    // ---- feedback
    public FeedbackService.Result submitFeedback(String game, int rating, String comment) {
        return feedback.submit(game, rating, comment);
    }

    // ---- refinement
    public List<String> refinementQuestions(int count) throws LLMException { return refinement.questions(count); }

    public ProfileManager.ApplyResult submitRefinement(List<RefinementService.QA> answers) throws LLMException {
        return refinement.interpret(answers);
    }

    // ---- misc
    public Optional<Game> lookupGame(String name) throws DataSourceException { return data.findByName(name); }
}
