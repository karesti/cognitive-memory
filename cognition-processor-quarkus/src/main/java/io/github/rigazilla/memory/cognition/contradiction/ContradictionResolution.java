package io.github.rigazilla.memory.cognition.contradiction;

/**
 * The outcome of resolving a {@link ContradictionPair}.
 *
 * <p>After resolution the superseded memory is updated in memory-service with
 * {@code status=superseded}, {@code superseded_by}, and {@code superseded_at} fields.
 * The winning memory is updated with a {@code supersedes} list referencing the loser's key.
 */
public record ContradictionResolution(

        /** Key of the memory that was kept active (the "winner"). */
        String winnerKey,

        /** Key of the memory that was superseded (the "loser"). May be {@code null} for coexistence. */
        String supersededKey,

        /** Resolution strategy that was applied. */
        ResolutionStrategy strategyApplied,

        /** Contradiction type identified by the detector. */
        ContradictionType contradictionType,

        /** LLM rationale for the contradiction identification. */
        String rationale,

        /** {@code true} when both memories were left active (coexistence strategy). */
        boolean coexistence
) {

    /** Factory for a resolved pair where {@code winnerKey} supersedes {@code loserKey}. */
    public static ContradictionResolution resolved(
            String winnerKey, String loserKey,
            ResolutionStrategy strategy, ContradictionType contradictionType, String rationale) {
        return new ContradictionResolution(
                winnerKey, loserKey, strategy, contradictionType, rationale, false);
    }

    /** Factory for a coexistence outcome — both memories remain active. */
    public static ContradictionResolution coexistence(
            String keyA, String keyB, ContradictionType contradictionType, String rationale) {
        return new ContradictionResolution(
                keyA, null, ResolutionStrategy.COEXISTENCE, contradictionType, rationale, true);
    }
}
