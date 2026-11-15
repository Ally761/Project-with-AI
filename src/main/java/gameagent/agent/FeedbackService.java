package gameagent.agent;

import gameagent.model.FeedbackResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import gameagent.data.DataSourceException;
import gameagent.data.GameDataSource;
import gameagent.llm.LLMClient;
import gameagent.llm.LLMException;
import gameagent.llm.LLMRequest;
import gameagent.llm.PromptBuilder;
import gameagent.model.Game;
import gameagent.model.GameFeedback;
import gameagent.profile.Category;
import gameagent.profile.ConflictResolver;
import gameagent.profile.ProfileManager;
import gameagent.profile.ProfileUpdate;
import gameagent.profile.UserProfile;
import gameagent.util.Json;
import gameagent.util.JsonException;

/**
 * Turns a rating + comment into profile changes. Hybrid: the rating-driven rules are deterministic;
 * the comment is interpreted by the LLM (skipped with a warning if the LLM is unavailable).
 */
public class FeedbackService {
    private final ProfileManager profiles;
    private final LLMClient llm;
    private final GameDataSource data;

    public FeedbackService(ProfileManager profiles, LLMClient llm, GameDataSource data) {
        this.profiles = profiles;
        this.llm = llm;
        this.data = data;
    }

    public FeedbackResult submit(String game, int rating, String comment) {
        if (game == null || game.isBlank()) throw new IllegalArgumentException("Game name is required.");
        if (rating < 1 || rating > 5) throw new IllegalArgumentException("Rating must be between 1 and 5.");
        String cmt = comment == null ? "" : comment.trim();
        List<String> changes = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // conflicting feedback detection
        for (GameFeedback old : profiles.profile().feedback()) {
            if (UserProfile.normalize(old.gameName()).equals(UserProfile.normalize(game)) && Math.abs(old.rating() - rating) >= 3) {
                warnings.add("This conflicts with your earlier rating of " + old.rating() + "/5 for " + game + "; the newer rating replaces it.");
            }
        }
        profiles.recordFeedback(new GameFeedback(game.trim(), rating, cmt, Instant.now()));

        // deterministic part
        ProfileUpdate u = new ProfileUpdate("feedback");
        if (rating >= 4) u.like(Category.GAME, game);
        else if (rating <= 2) u.dislike(Category.GAME, game);
        if (rating >= 4) {
            try {
                Optional<Game> g = data.findByName(game);
                if (g.isPresent()) for (String genre : g.get().genres()) u.like(Category.GENRE, genre);
            } catch (DataSourceException e) {
                warnings.add("Could not look up genres for " + game + " (" + e.getMessage() + ").");
            }
        }
        changes.addAll(profiles.apply(u, ConflictResolver.NEWEST_WINS).applied());

        // AI part
        if (!cmt.isEmpty()) {
            try {
                String raw = llm.complete(LLMRequest.json(PromptBuilder.interpretSystem(),
                        PromptBuilder.feedbackInterpretUser(profiles.profile().toPromptSummary(), game, rating, cmt)));
                ProfileUpdate fromComment = ProfileUpdate.fromJson(Json.parseLenientObject(raw), "feedback");
                changes.addAll(profiles.apply(fromComment, ConflictResolver.NEWEST_WINS).applied());
            } catch (LLMException e) {
                warnings.add("Your comment was saved but could not be analysed by the AI (" + e.getMessage() + ").");
            } catch (JsonException e) {
                warnings.add("Your comment was saved but the AI's analysis was unreadable.");
            }
        }
        return new FeedbackResult(changes, warnings);
    }
}
