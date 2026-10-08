package io.github.rigazilla.memory.cognition.process;

/**
 * Thrown when a caller attempts to start or trigger a cognitive process
 * that has been disabled via the enablement toggle.
 *
 * <p>This is an <em>operational</em> signal — not access control.
 * It means the operator has switched the process off; the request should be
 * retried after the process is re-enabled.
 *
 * <p>REST resources map this to <b>409 Conflict</b>.
 */
public class ProcessDisabledException extends RuntimeException {

    public ProcessDisabledException(String processId) {
        super("Process is disabled: " + processId);
    }
}
