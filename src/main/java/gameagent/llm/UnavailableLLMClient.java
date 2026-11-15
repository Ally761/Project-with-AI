package gameagent.llm;

/** Null-object used when no API key is configured: the app still starts, AI features report a clear error. */
public class UnavailableLLMClient implements LLMClient {
    private final String reason;

    public UnavailableLLMClient(String reason) {
        this.reason = reason;
    }

    @Override
    public String complete(LLMRequest request) throws LLMException {
        throw new LLMException(reason, 401);
    }

    @Override
    public String name() { return "unavailable"; }
}
