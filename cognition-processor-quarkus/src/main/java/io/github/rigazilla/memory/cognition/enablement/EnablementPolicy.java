package io.github.rigazilla.memory.cognition.enablement;

/**
 * Stable seam for deciding whether a cognitive process should run.
 *
 * <p>v1 has one implementation ({@link ProcessEnablementService}) that decides
 * on process id only. Richer implementations (user-scoped, client-scoped, etc.)
 * can be swapped in later without changing any gate call site, because all gates
 * already pass a full {@link EnablementContext}.
 *
 * <p>Implementations must be cheap — the event gate runs on the hot path.
 */
public interface EnablementPolicy {

    /**
     * Decide whether the process described by {@code ctx} should run.
     *
     * <p>Must never throw. Implementations that encounter an unexpected error
     * must fall back to the process default and log at WARN.
     *
     * @param ctx context for the decision; {@code ctx.processId()} is always present
     * @return the decision with a human-readable reason for observability
     */
    EnablementDecision decide(EnablementContext ctx);
}
