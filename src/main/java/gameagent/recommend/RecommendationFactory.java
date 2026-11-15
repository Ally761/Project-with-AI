package gameagent.recommend;

import gameagent.data.GameDataSource;
import gameagent.llm.LLMClient;
import gameagent.profile.ProfileManager;

/** Factory: the one place that knows how to build each {@link RecommendationStrategy}. New kinds (e.g. FRIENDS) go here. */
public class RecommendationFactory {
    public enum Type { PERSONALIZED, CURVEBALL }

    private final LLMClient llm;
    private final GameDataSource data;
    private final ProfileManager profiles;

    public RecommendationFactory(LLMClient llm, GameDataSource data, ProfileManager profiles) {
        this.llm = llm;
        this.data = data;
        this.profiles = profiles;
    }

    public RecommendationStrategy create(Type type) {
        switch (type) {
            case PERSONALIZED: return new PersonalizedStrategy(llm, data, profiles);
            case CURVEBALL: return new CurveballStrategy(llm, data, profiles);
            default: throw new IllegalArgumentException("Unknown recommendation type " + type);
        }
    }
}
