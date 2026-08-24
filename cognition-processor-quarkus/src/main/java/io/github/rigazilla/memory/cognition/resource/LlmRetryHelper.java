package io.github.rigazilla.memory.cognition.resource;

import io.github.rigazilla.memory.cognition.config.CognitionConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.function.Supplier;

/**
 * Shared helper that executes a supplier with exponential-backoff retry on
 * transient LLM failures (timeouts, 429 / 503 responses).
 *
 * <p>The retry policy is driven by three config properties so every deployment
 * context can tune behaviour without recompilation:
 * <ul>
 *   <li>{@code cognition.llm.retry.max-attempts} — maximum number of attempts
 *       (1 = no retry, 3 = two retries after the first failure).</li>
 *   <li>{@code cognition.llm.retry.initial-delay-ms} — wait before the first
 *       retry; doubles on each subsequent attempt.</li>
 *   <li>{@code cognition.llm.retry.max-delay-ms} — upper bound for the delay
 *       so the backoff does not grow without limit.</li>
 * </ul>
 *
 * <p>Any exception that escapes all attempts is re-thrown as-is so callers
 * can apply their own error handling (count errors, log, skip item, etc.).
 */
@ApplicationScoped
public class LlmRetryHelper {

    private static final Logger LOG = Logger.getLogger(LlmRetryHelper.class);

    @Inject
    CognitionConfig cognition;

    // Package-private fields for forTesting() override (no CDI required in unit tests)
    int maxAttemptsOverride = -1;
    long initialDelayMsOverride = -1;
    long maxDelayMsOverride = -1;

    /**
     * Create a pre-configured instance for use in unit tests (no CDI required).
     * Not for production use.
     */
    public static LlmRetryHelper forTesting(int maxAttempts, long initialDelayMs, long maxDelayMs) {
        LlmRetryHelper h = new LlmRetryHelper();
        h.maxAttemptsOverride = maxAttempts;
        h.initialDelayMsOverride = initialDelayMs;
        h.maxDelayMsOverride = maxDelayMs;
        return h;
    }

    /**
     * Execute {@code action} with retry and exponential backoff.
     *
     * @param <T>         return type of the LLM call
     * @param description short human-readable label for log messages
     * @param action      the LLM call to execute
     * @return the result of {@code action} on success
     * @throws RuntimeException the last exception if all attempts fail
     */
    public <T> T withRetry(String description, Supplier<T> action) {
        int configuredAttempts = maxAttemptsOverride >= 0
                ? maxAttemptsOverride
                : (cognition != null ? cognition.llm().retry().maxAttempts() : 3);
        long configuredInitialDelay = initialDelayMsOverride >= 0
                ? initialDelayMsOverride
                : (cognition != null ? cognition.llm().retry().initialDelayMs() : 1000L);
        long configuredMaxDelay = maxDelayMsOverride >= 0
                ? maxDelayMsOverride
                : (cognition != null ? cognition.llm().retry().maxDelayMs() : 30000L);

        int attempts = configuredAttempts < 1 ? 1 : configuredAttempts;
        long delayMs = configuredInitialDelay;
        RuntimeException lastException = null;

        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return action.get();
            } catch (RuntimeException e) {
                lastException = e;
                if (attempt < attempts) {
                    LOG.warnf("LLM call failed [%s] attempt %d/%d: %s — retrying in %dms",
                            description, attempt, attempts, e.getMessage(), delayMs);
                    sleep(delayMs);
                    delayMs = Math.min(delayMs * 2, configuredMaxDelay);
                } else {
                    LOG.errorf(e, "LLM call failed [%s] after %d attempt(s), giving up",
                            description, attempts);
                }
            }
        }

        // All attempts exhausted — re-throw the last exception.
        // lastException is always non-null here: the loop runs at least once (attempts >= 1)
        // and every iteration either returns or assigns lastException in the catch block.
        if (lastException == null) {
            throw new IllegalStateException("withRetry loop exited without result or exception");
        }
        throw lastException;
    }

    private void sleep(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
