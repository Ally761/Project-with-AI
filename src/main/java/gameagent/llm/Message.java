package gameagent.llm;

/** One turn of a conversation sent to an LLM. */
public record Message(Role role, String content) {
    public static Message user(String text) { return new Message(Role.USER, text); }
    public static Message assistant(String text) { return new Message(Role.ASSISTANT, text); }
}
