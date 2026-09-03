package io.github.rigazilla.memory.cognition.contradiction;

/**
 * A pair of memory keys (and their content) that may contradict each other.
 * Passed to {@link ContradictionDetector} for LLM analysis.
 */
public record ContradictionPair(

        /** Memory-service key of the first (typically older) memory. */
        String keyA,

        /** Content text of the first memory. */
        String contentA,

        /** ISO-8601 observed_at timestamp of memory A (may be null if missing). */
        String observedAtA,

        /** Confidence score of memory A (0.0–1.0). */
        double confidenceA,

        /** Memory-service key of the second (typically newer) memory. */
        String keyB,

        /** Content text of the second memory. */
        String contentB,

        /** ISO-8601 observed_at timestamp of memory B (may be null if missing). */
        String observedAtB,

        /** Confidence score of memory B (0.0–1.0). */
        double confidenceB,

        /** Memory type shared by both memories (e.g. {@code "preference"}, {@code "fact"}). */
        String memoryType
) {}
