package gameagent.recommend;

import gameagent.data.GameDataSource;
import gameagent.llm.LLMClient;
import gameagent.profile.ProfileManager;

/** Concrete creator: deliberately different picks outside the user's usual tastes. */
public class CurveballRecommender extends Recommender {
    private final LLMClient llm;
    private final GameDataSource data;
    private final ProfileManager profiles;

    public CurveballRecommender(LLMClient llm, GameDataSource data, ProfileManager profiles, GameCatalog catalog) {
        super(catalog, false);
        this.llm = llm;
        this.data = data;
        this.profiles = profiles;
    }

    @Override
    protected RecommendationStrategy createStrategy() {
        return new CurveballStrategy(llm, data, profiles);
    }
}
