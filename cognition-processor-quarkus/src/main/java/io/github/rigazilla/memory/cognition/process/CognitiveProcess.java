package io.github.rigazilla.memory.cognition.process;

import io.github.rigazilla.memory.cognition.resource.ResourceRequirements;

import java.util.List;
import java.util.Map;

/**
 * Common contract for managed cognitive processes.
 */
public interface CognitiveProcess {

    String id();

    String displayName();

    String description();

    boolean supportsStart();

    boolean supportsEnable();

    boolean supportsDisable();

    ManagedProcessState state();

    ManagedProcessInspection inspect();

    default void start() {
        throw new UnsupportedOperationException("start is not implemented for process " + id());
    }

    /**
     * Start with optional parameters. The default ignores params and delegates to
     * {@link #start()}, preserving backward compatibility for processes that do not
     * support parameterised starts.
     *
     * @param params arbitrary key/value pairs; processes that recognise specific keys
     *               (e.g. {@code "namespacePrefix"}) will act on them; all others ignore them.
     */
    default void start(Map<String, List<String>> params) {
        start();
    }

    default void enable() {
        throw new UnsupportedOperationException("enable is not implemented for process " + id());
    }

    default void disable() {
        throw new UnsupportedOperationException("disable is not implemented for process " + id());
    }

    /**
     * Get resource requirements for this process.
     * Returns null to use global defaults only.
     *
     * @return The resource requirements, or null for global defaults
     */
    default ResourceRequirements getResourceRequirements() {
        return null;
    }

    /**
     * Default enablement when nothing has toggled this process at runtime.
     *
     * <p>Processes that should be off-by-default (e.g. benchmarking baselines) can
     * override this to return {@code false}. All v1 processes keep {@code true},
     * preserving today's always-on behaviour.
     *
     * @return {@code true} if this process should run when no explicit toggle has been set
     */
    default boolean defaultEnabled() {
        return true;
    }
}
