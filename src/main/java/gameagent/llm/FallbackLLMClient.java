package gameagent.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Decorator/chain: tries the primary model, and if it is overloaded, rate limited, unreachable or missing,
 * moves on to the next model. Errors that would hit every model the same way (bad key, malformed request)
 * stop the chain immediately. If everything fails, the exception lists the exact error from each model.
 */
public class FallbackLLMClient implements LLMClient {
    private final List<LLMClient> chain;
    private final Consumer<String> notifier;

    public FallbackLLMClient(List<LLMClient> chain, Consumer<String> notifier) {
        if (chain.isEmpty()) throw new IllegalArgumentException("chain must not be empty");
        this.chain = chain;
        this.notifier = notifier == null ? s -> { } : notifier;
    }

    @Override
    public String name() { return chain.get(0).name(); }

    @Override
    public String complete(LLMRequest request) throws LLMException {
        List<String> errors = new ArrayList<>();
        LLMException first = null;
        for (int i = 0; i < chain.size(); i++) {
            LLMClient c = chain.get(i);
            try {
                return c.complete(request);
            } catch (LLMException e) {
                if (first == null) first = e;
                errors.add(c.name() + " -> " + e.getMessage());
                boolean worthTryingNext = e.isRetryable() || e.status() == 404;
                if (!worthTryingNext || i == chain.size() - 1) break;
                notifier.accept("  (" + c.name() + " failed; switching to " + chain.get(i + 1).name() + ")");
            }
        }
        String msg = errors.size() == 1 ? errors.get(0) : "All models failed: " + String.join(" | ", errors);
        throw new LLMException(msg, first.status(), first);
    }
}
