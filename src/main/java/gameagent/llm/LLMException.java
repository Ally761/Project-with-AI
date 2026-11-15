package gameagent.llm;

/** Any failure talking to an LLM. Carries the HTTP status (0 if none) so callers can decide whether to retry. */
public class LLMException extends Exception {
    private final int status;

    public LLMException(String message) {
        this(message, 0, null);
    }

    public LLMException(String message, int status) {
        this(message, status, null);
    }

    public LLMException(String message, int status, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public int status() { return status; }

    /** Rate limits, server errors and network failures are worth retrying; auth/bad-request errors are not. */
    public boolean isRetryable() {
        return status == 429 || status >= 500 || status == 0 && getCause() != null;
    }
}
