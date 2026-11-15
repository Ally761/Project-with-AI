package gameagent.agent;

import gameagent.model.RefinementAnswer;
import gameagent.model.ApplyResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import gameagent.llm.LLMClient;
import gameagent.llm.LLMException;
import gameagent.llm.LLMRequest;
import gameagent.llm.PromptBuilder;
import gameagent.profile.ConflictResolver;
import gameagent.profile.ProfileManager;
import gameagent.profile.ProfileUpdate;
import gameagent.util.Json;
import gameagent.util.JsonException;

/**
 * Preference refinement module: finds gaps in the profile (deterministic), has the LLM write targeted questions,
 * then has the LLM turn the user's free-text answers into profile updates.
 */
public class RefinementService {
    private final ProfileManager profiles;
    private final LLMClient llm;

    public RefinementService(ProfileManager profiles, LLMClient llm) {
        this.profiles = profiles;
        this.llm = llm;
    }

    /** Empty list means the profile has no known gaps. */
    public List<String> questions(int count) throws LLMException {
        List<String> gaps = profiles.profile().gaps();
        if (gaps.isEmpty()) return List.of();
        String raw = llm.complete(LLMRequest.json(PromptBuilder.refinementQuestionsSystem(),
                PromptBuilder.refinementQuestionsUser(profiles.profile().toPromptSummary(), gaps, count)));
        try {
            List<String> qs = new ArrayList<>(Json.strList(Json.parseLenientObject(raw), "questions"));
            return qs.size() > count ? qs.subList(0, count) : qs;
        } catch (JsonException e) {
            throw new LLMException("The AI's questions were unreadable; please try again.");
        }
    }

    public ApplyResult interpret(List<RefinementAnswer> answers) throws LLMException {
        StringBuilder qa = new StringBuilder();
        for (RefinementAnswer a : answers) {
            if (a.answer() == null || a.answer().isBlank()) continue;
            qa.append("Q: ").append(a.question()).append("\nA: ").append(a.answer().trim()).append("\n\n");
        }
        if (qa.length() == 0) return new ApplyResult(List.of(), List.of());
        String raw = llm.complete(LLMRequest.json(PromptBuilder.interpretSystem(),
                PromptBuilder.refinementInterpretUser(profiles.profile().toPromptSummary(), qa.toString())));
        Map<String, Object> json;
        try {
            json = Json.parseLenientObject(raw);
        } catch (JsonException e) {
            throw new LLMException("The AI's interpretation was unreadable; please try again.");
        }
        return profiles.apply(ProfileUpdate.fromJson(json, "refinement"), ConflictResolver.NEWEST_WINS);
    }
}
