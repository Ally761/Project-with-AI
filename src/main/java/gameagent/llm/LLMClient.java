package gameagent.llm;

/**
 * The only thing the rest of the application knows about "the model".
 * To support a new provider: implement this interface and add a case in {@link LLMClientFactory}.
 */
public interface LLMClient {
    /** Sends the request and returns the model's raw text output. */
    String complete(LLMRequest request) throws LLMException;

    /** Human readable label, e.g. "gemini:gemini-2.5-flash". */
    String name();
}
