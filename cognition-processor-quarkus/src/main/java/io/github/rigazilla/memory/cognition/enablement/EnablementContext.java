package io.github.rigazilla.memory.cognition.enablement;

import java.util.Map;
import java.util.Optional;

/**
 * Context passed to {@link EnablementPolicy#decide} for each gate evaluation.
 *
 * <p>v1 populates {@code processId} and, where trivially available,
 * {@code conversationId}. The remaining fields are carried through the API
 * so a richer policy can be dropped in later without changing any call site.
 */
public record EnablementContext(
        String processId,
        Optional<String> userId,
        Optional<String> clientId,
        Optional<String> conversationId,
        Map<String, String> attributes) {

    /**
     * Convenience factory for call sites that only have a process id.
     */
    public static EnablementContext of(String processId) {
        return new EnablementContext(
                processId,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Map.of());
    }
}
