package io.github.rigazilla.memory.cognition.token;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Application-scoped registry of token usage counters.
 *
 * <p>Each LLM resource within a cognitive process gets its own {@link TokenUsage} counter,
 * keyed by the combination of process ID and resource name. In addition, every process has
 * an aggregate counter that sums all of its resources. Counters are created lazily
 * on first access and live for the lifetime of the application.
 *
 * <p>Usage is also published to Micrometer (and therefore to {@code /q/metrics} in Prometheus
 * format) as counters labelled with {@code process}, {@code resource}, {@code provider} and
 * {@code model}:
 * <ul>
 *   <li>{@code llm_tokens_input_total}</li>
 *   <li>{@code llm_tokens_output_total}</li>
 *   <li>{@code llm_tokens_total}</li>
 *   <li>{@code llm_requests_total}</li>
 * </ul>
 *
 * <p>Callers record token usage after each LLM call:
 * <pre>{@code
 *   tokenTracker.record("durable-memory-extraction", "extractor", inputTokens, outputTokens);
 * }</pre>
 *
 * <p>Process inspect() methods retrieve snapshots for inclusion in the API response:
 * <pre>{@code
 *   TokenUsage.Snapshot snap = tokenTracker.snapshot("durable-memory-extraction", "extractor");
 *   TokenUsage.Snapshot all  = tokenTracker.processSnapshot("durable-memory-extraction");
 * }</pre>
 */
@ApplicationScoped
public class TokenTracker {

    static final String UNKNOWN = "unknown";

    @Inject
    MeterRegistry meterRegistry;

    private final ConcurrentHashMap<String, TokenUsage> counters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TokenUsage> processCounters = new ConcurrentHashMap<>();

    /**
     * Record token usage for one LLM call made by the named resource inside the given process.
     *
     * <p>Provider and model are reported to Micrometer as {@code unknown}.
     *
     * @param processId    cognitive process identifier (e.g. {@code "durable-memory-extraction"})
     * @param resourceName LLM resource name within the process (e.g. {@code "extractor"})
     * @param inputTokens  prompt tokens consumed (may be null — treated as zero)
     * @param outputTokens completion tokens produced (may be null — treated as zero)
     */
    public void record(String processId, String resourceName,
            Integer inputTokens, Integer outputTokens) {
        record(processId, resourceName, null, null, inputTokens, outputTokens);
    }

    /**
     * Record token usage for one LLM call, including the provider and model that served it.
     *
     * @param processId    cognitive process identifier
     * @param resourceName LLM resource name within the process
     * @param provider     LLM provider (e.g. {@code "OLLAMA"}); null is reported as {@code unknown}
     * @param model        model name; null is reported as {@code unknown}
     * @param inputTokens  prompt tokens consumed (may be null — treated as zero)
     * @param outputTokens completion tokens produced (may be null — treated as zero)
     */
    public void record(String processId, String resourceName, String provider, String model,
            Integer inputTokens, Integer outputTokens) {
        counters.computeIfAbsent(key(processId, resourceName), k -> new TokenUsage())
                .record(inputTokens, outputTokens);
        processCounters.computeIfAbsent(processId, k -> new TokenUsage())
                .record(inputTokens, outputTokens);
        publish(processId, resourceName, provider, model, inputTokens, outputTokens);
    }

    /**
     * Return an immutable point-in-time snapshot for the named resource.
     *
     * <p>Returns a zero-valued snapshot when no calls have been recorded yet
     * so callers never receive null.
     *
     * @param processId    cognitive process identifier
     * @param resourceName LLM resource name
     * @return snapshot of current counters
     */
    public TokenUsage.Snapshot snapshot(String processId, String resourceName) {
        TokenUsage usage = counters.get(key(processId, resourceName));
        return usage != null ? usage.snapshot() : new TokenUsage().snapshot();
    }

    /**
     * Return an immutable snapshot aggregated over every resource of the given process.
     *
     * <p>Returns a zero-valued snapshot when the process has not recorded any calls yet.
     *
     * @param processId cognitive process identifier
     * @return snapshot of the process-level counters
     */
    public TokenUsage.Snapshot processSnapshot(String processId) {
        TokenUsage usage = processCounters.get(processId);
        return usage != null ? usage.snapshot() : new TokenUsage().snapshot();
    }

    private void publish(String processId, String resourceName, String provider, String model,
            Integer inputTokens, Integer outputTokens) {
        long in = inputTokens != null ? inputTokens : 0L;
        long out = outputTokens != null ? outputTokens : 0L;
        String[] tags = {
            "process", processId,
            "resource", resourceName,
            "provider", provider != null ? provider : UNKNOWN,
            "model", model != null ? model : UNKNOWN
        };
        meterRegistry.counter("llm.tokens.input", tags).increment(in);
        meterRegistry.counter("llm.tokens.output", tags).increment(out);
        meterRegistry.counter("llm.tokens", tags).increment(in + out);
        meterRegistry.counter("llm.requests", tags).increment();
    }

    private static String key(String processId, String resourceName) {
        return processId + ":" + resourceName;
    }
}
