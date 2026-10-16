package io.github.rigazilla.memory.cognition.token;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe token usage counter for a single LLM resource within a cognitive process.
 *
 * <p>Tracks cumulative token consumption across all LLM calls made by one named resource
 * (e.g. "extractor", "verifier"). All counters are backed by {@link AtomicLong} so they
 * can be incremented from multiple threads without synchronization.
 *
 * <p>Metric names follow Prometheus conventions:
 * <ul>
 *   <li>{@code llm_tokens_input_total}</li>
 *   <li>{@code llm_tokens_output_total}</li>
 *   <li>{@code llm_tokens_total}</li>
 *   <li>{@code llm_requests_total}</li>
 * </ul>
 */
public final class TokenUsage {

    private final AtomicLong inputTokensTotal = new AtomicLong(0);
    private final AtomicLong outputTokensTotal = new AtomicLong(0);
    private final AtomicLong tokensTotal = new AtomicLong(0);
    private final AtomicLong requestsTotal = new AtomicLong(0);

    /**
     * Record token usage from one LLM call.
     *
     * <p>Any null count is treated as zero so callers do not need to guard against
     * providers that omit individual token fields.
     *
     * @param inputTokens  prompt tokens consumed (may be null)
     * @param outputTokens completion tokens produced (may be null)
     */
    public void record(Integer inputTokens, Integer outputTokens) {
        long in = inputTokens != null ? inputTokens : 0L;
        long out = outputTokens != null ? outputTokens : 0L;
        inputTokensTotal.addAndGet(in);
        outputTokensTotal.addAndGet(out);
        tokensTotal.addAndGet(in + out);
        requestsTotal.incrementAndGet();
    }

    /** Cumulative prompt tokens sent to the LLM ({@code llm_tokens_input_total}). */
    public long getInputTokensTotal() {
        return inputTokensTotal.get();
    }

    /** Cumulative completion tokens received from the LLM ({@code llm_tokens_output_total}). */
    public long getOutputTokensTotal() {
        return outputTokensTotal.get();
    }

    /** Cumulative input + output tokens ({@code llm_tokens_total}). */
    public long getTokensTotal() {
        return tokensTotal.get();
    }

    /** Cumulative number of LLM API calls ({@code llm_requests_total}). */
    public long getRequestsTotal() {
        return requestsTotal.get();
    }

    /**
     * Average tokens per request (gauge — computed from counters).
     *
     * @return average tokens per request, or 0 when no requests have been made
     */
    public long getAverageTokensPerRequest() {
        long reqs = requestsTotal.get();
        return reqs == 0 ? 0 : tokensTotal.get() / reqs;
    }

    /**
     * Return an immutable snapshot suitable for serialisation in inspect() responses.
     *
     * @return snapshot record
     */
    public Snapshot snapshot() {
        long reqs = requestsTotal.get();
        long total = tokensTotal.get();
        return new Snapshot(
            inputTokensTotal.get(),
            outputTokensTotal.get(),
            total,
            reqs,
            reqs == 0 ? 0 : total / reqs
        );
    }

    /**
     * Immutable point-in-time snapshot of token usage for one LLM resource.
     *
     * <p>Field names intentionally match the expected API response format from the
     * requirements document so JSON serialisation works without extra mapping.
     */
    public record Snapshot(
        long inputTokens,
        long outputTokens,
        long totalTokens,
        long requestCount,
        long averageTokensPerRequest
    ) {
    }
}
