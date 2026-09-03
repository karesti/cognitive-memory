package io.github.rigazilla.memory.cognition.contradiction;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Structured response from the LLM contradiction detector.
 *
 * <p>The LLM is asked to compare two memory contents and report whether they
 * contradict each other, the type of contradiction, the recommended resolution
 * strategy, and a brief rationale.
 */
public record ContradictionDetectionResponse(

        /** {@code true} when the two memories express contradictory claims. */
        boolean contradicts,

        /** High-level category of the contradiction, or {@link ContradictionType#NONE}. */
        ContradictionType contradictionType,

        /** Recommended resolution strategy. */
        ResolutionStrategy recommendedStrategy,

        /** One-sentence rationale explaining the contradiction (or why there is none). */
        String rationale
) {

    @JsonCreator
    public static ContradictionDetectionResponse create(
            @JsonProperty("contradicts") Boolean contradicts,
            @JsonProperty("contradictionType") String contradictionType,
            @JsonProperty("recommendedStrategy") String recommendedStrategy,
            @JsonProperty("rationale") String rationale) {
        return new ContradictionDetectionResponse(
                contradicts != null && contradicts,
                ContradictionType.fromValue(contradictionType),
                ResolutionStrategy.fromValue(recommendedStrategy),
                rationale != null ? rationale : ""
        );
    }

    /** Convenience: returns {@code true} when the LLM found a coexistence case. */
    public boolean isCoexistence() {
        return contradicts && recommendedStrategy == ResolutionStrategy.COEXISTENCE;
    }
}
