package gameagent.llm;

import java.util.List;

/** Provider-neutral request. Every LLMClient implementation translates this to its own wire format. */
public record LLMRequest(String systemPrompt, List<Message> messages, boolean jsonMode, double temperature) {

    /** Single-turn request that expects a JSON object back. */
    public static LLMRequest json(String system, String user) {
        return new LLMRequest(system, List.of(Message.user(user)), true, 0.4);
    }

    public static LLMRequest json(String system, List<Message> messages, double temperature) {
        return new LLMRequest(system, messages, true, temperature);
    }
}
