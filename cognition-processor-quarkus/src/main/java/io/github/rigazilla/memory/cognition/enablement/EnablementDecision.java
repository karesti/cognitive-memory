package io.github.rigazilla.memory.cognition.enablement;

/**
 * Operational decision returned by {@link EnablementPolicy#decide}.
 *
 * <p>The {@code reason} string is intended for observability and logging only —
 * it must never be parsed or branched on by callers.
 */
public record EnablementDecision(boolean enabled, String reason) {
}
