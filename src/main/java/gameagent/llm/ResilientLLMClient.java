package gameagent.llm;

import java.util.function.Consumer;

/**
 * Decorator that adds free-tier friendliness to any {@link LLMClient}:
 * a minimum gap between calls (throttling) and retry with exponential backoff on 429 / 5xx / network errors.
 * Every retry is announced through the notifier so the user can see what is happening instead of a silent wait.
 */
public class ResilientLLMClient implements LLMClient {
    private final LLMClient delegate;
    private final long minIntervalMs;
    private final int maxRetries;
    private final long baseBackoffMs;
    private final Consumer<String> notifier;
    private long lastCallEnd = 0;

    public ResilientLLMClient(LLMClient delegate, long minIntervalMs, int maxRetries, long baseBackoffMs, Consumer<String> notifier) {
        this.delegate = delegate;
        this.minIntervalMs = minIntervalMs;
        this.maxRetries = maxRetries;
        this.baseBackoffMs = baseBackoffMs;
        this.notifier = notifier == null ? s -> { } : notifier;
    }

    public ResilientLLMClient(LLMClient delegate, long minIntervalMs, int maxRetries, long baseBackoffMs) {
        this(delegate, minIntervalMs, maxRetries, baseBackoffMs, null);
    }

    public ResilientLLMClient(LLMClient delegate, long minIntervalMs) {
        this(delegate, minIntervalMs, 3, 2000, null);
    }

    @Override
    public String name() { return delegate.name(); }

    @Override
    public String complete(LLMRequest request) throws LLMException {
        LLMException last = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            throttle();
            try {
                String out = delegate.complete(request);
                markDone();
                return out;
            } catch (LLMException e) {
                markDone();
                last = e;
                if (!e.isRetryable() || attempt == maxRetries) break;
                long wait = baseBackoffMs * (1L << attempt);
                notifier.accept("  (" + delegate.name() + " problem: " + shorten(e.getMessage()) + " - retry "
                        + (attempt + 1) + "/" + maxRetries + " in " + Math.max(1, wait / 1000) + "s)");
                sleep(wait);
            }
        }
        throw last;
    }

    private static String shorten(String s) {
        return s.length() > 140 ? s.substring(0, 137) + "..." : s;
    }

    private synchronized void throttle() throws LLMException {
        long wait = lastCallEnd + minIntervalMs - System.currentTimeMillis();
        if (wait > 0) sleep(wait);
    }

    private synchronized void markDone() {
        lastCallEnd = System.currentTimeMillis();
    }

    private static void sleep(long ms) throws LLMException {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LLMException("Interrupted", 0, e);
        }
    }
}
