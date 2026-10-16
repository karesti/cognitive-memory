package io.github.rigazilla.memory.cognition.token;

import java.util.function.Supplier;

/**
 * Thread-local carrier that propagates the active (processId, resourceName) context
 * from the LLM call site to {@link TokenUsageListener}.
 *
 * <p>Usage pattern:
 * <pre>{@code
 *   TokenTrackingContext.set("durable-memory-extraction", "extractor");
 *   try {
 *       result = extractor.extract(evidence);
 *   } finally {
 *       TokenTrackingContext.clear();
 *   }
 * }</pre>
 *
 * <p>{@link #clear()} must always be called in a {@code finally} block to prevent
 * context leakage across virtual threads or thread-pool reuse.
 */
public final class TokenTrackingContext {

    /** Immutable context entry carried on the current thread. */
    public record Entry(String processId, String resourceName) {
    }

    private static final ThreadLocal<Entry> CONTEXT = new ThreadLocal<>();

    private TokenTrackingContext() {
    }

    /**
     * Set the active tracking context for the current thread.
     *
     * @param processId    cognitive process identifier
     * @param resourceName LLM resource name within the process
     */
    public static void set(String processId, String resourceName) {
        CONTEXT.set(new Entry(processId, resourceName));
    }

    /**
     * Return the active context entry, or {@code null} when no context is set.
     *
     * @return current entry or null
     */
    public static Entry current() {
        return CONTEXT.get();
    }

    /**
     * Clear the active context for the current thread.
     * Must be called in a {@code finally} block after the LLM invocation.
     */
    public static void clear() {
        CONTEXT.remove();
    }

    /**
     * Run {@code action} with the tracking context set, always clearing it afterwards.
     *
     * <p>The action must run on the calling thread, because the context is thread-local.
     *
     * @param processId    cognitive process identifier
     * @param resourceName LLM resource name within the process
     * @param action       the LLM invocation to track
     * @param <T>          result type
     * @return the result of {@code action}
     */
    public static <T> T call(String processId, String resourceName, Supplier<T> action) {
        set(processId, resourceName);
        try {
            return action.get();
        } finally {
            clear();
        }
    }
}
