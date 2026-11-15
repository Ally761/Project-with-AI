package gameagent.recommend;

import gameagent.data.GameDataSource;
import gameagent.llm.LLMClient;
import gameagent.profile.ProfileManager;

/** Concrete creator: games tailored to the user's profile and request. */
public class PersonalizedRecommender extends Recommender {
    private final LLMClient llm;
    private final GameDataSource data;
    private final ProfileManager profiles;

    public PersonalizedRecommender(LLMClient llm, GameDataSource data, ProfileManager profiles, GameCatalog catalog) {
        super(catalog, true);
        this.llm = llm;
        this.data = data;
        this.profiles = profiles;
    }

    @Override
    protected RecommendationStrategy createStrategy() {
        return new PersonalizedStrategy(llm, data, profiles);
    }
}
