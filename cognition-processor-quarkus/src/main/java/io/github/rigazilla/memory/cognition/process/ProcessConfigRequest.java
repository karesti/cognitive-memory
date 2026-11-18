package io.github.rigazilla.memory.cognition.process;

import java.util.Map;

/**
 * Request body for {@code POST /api/processes/{id}/config}.
 *
 * <p>Allows generic configuration of cognitive processes. The settings must be nested
 * under a top-level {@code "config"} key. Each process interprets the configuration map
 * according to its own schema. Unrecognised keys are silently ignored.
 *
 * <p>Example — disable the verifier stage of the durable memory extraction process:
 * <pre>{@code
 * {
 *   "config": {
 *     "verifierEnabled": false
 *   }
 * }
 * }</pre>
 */
public record ProcessConfigRequest(
        Map<String, Object> config
) {
}
