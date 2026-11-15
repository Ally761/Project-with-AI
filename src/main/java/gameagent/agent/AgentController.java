package gameagent.agent;

import gameagent.model.SteamLinkResult;
import gameagent.model.RefinementAnswer;
import gameagent.model.FeedbackResult;
import gameagent.model.ApplyResult;
import java.util.List;
import java.util.Optional;

import gameagent.calendar.CalendarException;
import gameagent.calendar.CalendarGame;
import gameagent.calendar.CalendarPlan;
import gameagent.calendar.CalendarService;
import gameagent.calendar.PlaySlot;
import gameagent.calendar.ScheduleMode;
import gameagent.data.DataSourceException;
import gameagent.data.GameDataSource;
import gameagent.friends.CommonGames;
import gameagent.friends.Friend;
import gameagent.friends.FriendException;
import gameagent.friends.FriendService;
import gameagent.llm.LLMClient;
import gameagent.llm.LLMException;
import gameagent.model.Game;
import gameagent.news.NewsException;
import gameagent.playtime.PlaytimeException;
import gameagent.playtime.PlaytimeResult;
import gameagent.playtime.PlaytimeService;
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
import gameagent.steam.SteamLinkService;
import gameagent.steam.SteamProfileException;

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
    private final SteamLinkService steam;
    private final CalendarService calendar;
    private final PlaytimeService playtime;
    private final FriendService friends;

    public AgentController(LLMClient llm, ProfileManager profiles, ChatAgent chatAgent, ConversationMemory memory,
                           RecommendationService recommendations, NewsService news, FeedbackService feedback,
                           RefinementService refinement, GameDataSource data, SteamLinkService steam,
                           CalendarService calendar, PlaytimeService playtime, FriendService friends) {
        this.llm = llm;
        this.profiles = profiles;
        this.chatAgent = chatAgent;
        this.memory = memory;
        this.recommendations = recommendations;
        this.news = news;
        this.feedback = feedback;
        this.refinement = refinement;
        this.data = data;
        this.steam = steam;
        this.calendar = calendar;
        this.playtime = playtime;
        this.friends = friends;
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

    public ApplyResult updateProfile(ProfileUpdate update, ConflictResolver resolver) {
        return profiles.apply(update, resolver);
    }

    public boolean removeNote(int index) { return profiles.removeNote(index); }

    public void resetProfile() {
        profiles.reset();
        recommendations.catalog().clear();
        memory.clear();
    }

    // ---- feedback
    public FeedbackResult submitFeedback(String game, int rating, String comment) {
        return feedback.submit(game, rating, comment);
    }

    // ---- refinement
    public List<String> refinementQuestions(int count) throws LLMException { return refinement.questions(count); }

    public ApplyResult submitRefinement(List<RefinementAnswer> answers) throws LLMException {
        return refinement.interpret(answers);
    }

    // ---- steam
    /** Links a public Steam profile (link, 17-digit ID or custom URL name) and imports tastes from its library. */
    public SteamLinkResult linkSteam(String profile) throws SteamProfileException { return steam.link(profile); }

    /** Re-reads the linked profile. */
    public SteamLinkResult refreshSteam() throws SteamProfileException { return steam.refresh(); }

    /** Forgets the link and library; optionally removes the preferences that were imported from it. */
    public List<String> unlinkSteam(boolean removeImported) { return steam.unlink(removeImported); }

    // ---- gaming calendar
    /** Replaces weekly availability. Accepts "mon,wed 20:00-22:00 chill; sat 14:00-18:00" or plain wording. */
    public List<PlaySlot> setAvailability(String text) throws CalendarException { return calendar.setAvailability(text); }

    public List<PlaySlot> availability() { return calendar.availability(); }

    public void addCalendarGame(String name, Double hours) throws CalendarException { calendar.addGame(name, hours); }

    public boolean removeCalendarGame(String name) { return calendar.removeGame(name); }

    public List<CalendarGame> calendarGames() { return calendar.games(); }

    /** @param weeks 1 = weekly itinerary, 4 = monthly */
    public CalendarPlan createCalendar(int weeks, ScheduleMode mode) throws CalendarException {
        profiles.log("calendar", weeks + " weeks");
        return calendar.createCalendar(weeks, mode);
    }

    public CalendarPlan refineCalendar(String instruction) throws CalendarException { return calendar.refineCalendar(instruction); }

    public Optional<CalendarPlan> currentCalendar() { return calendar.current(); }

    public boolean calendarIsStale() { return calendar.isStale(); }

    public void resetCalendarAdjustments() { calendar.resetAdjustments(); }

    // ---- playtime
    /** How long the game takes this user: typical time, adjusted to their pace when their history allows. */
    public PlaytimeResult estimatePlaytime(String game) throws PlaytimeException {
        profiles.log("playtime", game == null ? "" : game);
        return playtime.estimate(game);
    }

    // ---- friends
    /** Adds a friend by their public Steam profile link (no login or API key). */
    public Friend addFriend(String profile) throws SteamProfileException { return friends.add(profile); }

    public boolean removeFriend(String name) { return friends.remove(name); }

    public List<Friend> friends() { return friends.friends(); }

    /** @return problems met while re-reading friends (e.g. a profile that has since gone private) */
    public List<String> refreshFriends() { return friends.refresh(); }

    public CommonGames commonGames(String friend) throws FriendException { return friends.common(friend); }

    /** Games the friends play that suit the user. @param friend one friend's name, or blank for everyone */
    public RecommendationResult recommendFromFriends(String friend, int count) throws RecommendationException {
        profiles.log("friends", friend == null ? "" : friend);
        return recommendations.fromFriends(friend, count);
    }

    // ---- misc
    public Optional<Game> lookupGame(String name) throws DataSourceException { return data.findByName(name); }
}
