package gameagent.recommend;

import gameagent.data.GameDataSource;
import gameagent.friends.FriendRoster;
import gameagent.llm.LLMClient;
import gameagent.profile.ProfileManager;

/** Concrete creator: games the user's friends play that suit the user. */
public class FriendsRecommender extends Recommender {
    private final LLMClient llm;
    private final GameDataSource data;
    private final ProfileManager profiles;
    private final FriendRoster friends;

    public FriendsRecommender(LLMClient llm, GameDataSource data, ProfileManager profiles, FriendRoster friends, GameCatalog catalog) {
        super(catalog, false);
        this.llm = llm;
        this.data = data;
        this.profiles = profiles;
        this.friends = friends;
    }

    @Override
    protected RecommendationStrategy createStrategy() {
        return new FriendsStrategy(llm, data, profiles, friends);
    }
}
