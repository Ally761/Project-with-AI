package gameagent.recommend;

import gameagent.data.GameDataSource;
import gameagent.friends.FriendRoster;
import gameagent.llm.LLMClient;
import gameagent.profile.ProfileManager;

/** Factory: the one place that knows how to build each {@link RecommendationStrategy}. New kinds (e.g. FRIENDS) go here. */
public class RecommendationFactory {
    public enum Type { PERSONALIZED, CURVEBALL, FRIENDS }

    private final LLMClient llm;
    private final GameDataSource data;
    private final ProfileManager profiles;

    private final FriendRoster friends;

    public RecommendationFactory(LLMClient llm, GameDataSource data, ProfileManager profiles) {
        this(llm, data, profiles, FriendRoster.empty());
    }

    public RecommendationFactory(LLMClient llm, GameDataSource data, ProfileManager profiles, FriendRoster friends) {
        this.llm = llm;
        this.data = data;
        this.profiles = profiles;
        this.friends = friends;
    }

    public RecommendationStrategy create(Type type) {
        switch (type) {
            case PERSONALIZED: return new PersonalizedStrategy(llm, data, profiles);
            case CURVEBALL: return new CurveballStrategy(llm, data, profiles);
            case FRIENDS: return new FriendsStrategy(llm, data, profiles, friends);
            default: throw new IllegalArgumentException("Unknown recommendation type " + type);
        }
    }
}
