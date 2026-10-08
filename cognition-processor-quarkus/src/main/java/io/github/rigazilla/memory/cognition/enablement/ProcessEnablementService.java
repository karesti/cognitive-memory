package io.github.rigazilla.memory.cognition.enablement;

import io.github.rigazilla.memory.cognition.process.CognitiveProcess;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.concurrent.ConcurrentHashMap;

/**
 * v1 {@link EnablementPolicy} implementation and mutable store for the REST toggle.
 *
 * <p>Decides on {@code processId} only. The {@code ConcurrentHashMap} is volatile
 * in-memory state — it resets on restart (persistence is deferred to a later version).
 *
 * <p><b>Circular-dependency safety:</b> {@link io.github.rigazilla.memory.cognition.process.CognitiveProcessRegistry}
 * eagerly instantiates every {@link CognitiveProcess} bean in its constructor; each process
 * will inject this service. To break the cycle, {@code defaultEnabled()} is resolved
 * <em>lazily</em> at {@code decide()} time via an {@link Instance} — never in a constructor
 * or {@code @PostConstruct}.
 */
@ApplicationScoped
public class ProcessEnablementService implements EnablementPolicy {

    private static final Logger LOG = Logger.getLogger(ProcessEnablementService.class);

    /** Explicit runtime overrides: processId → enabled. Empty at startup. Package-private for test reset. */
    final ConcurrentHashMap<String, Boolean> overrides = new ConcurrentHashMap<>();

    /**
     * Lazy handle to all {@link CognitiveProcess} beans.
     * Resolved inside {@code decide()} — never touched during construction.
     */
    @Inject
    Instance<CognitiveProcess> processes;

    // -------------------------------------------------------------------------
    // EnablementPolicy
    // -------------------------------------------------------------------------

    @Override
    public EnablementDecision decide(EnablementContext ctx) {
        try {
            Boolean override = overrides.get(ctx.processId());
            if (override != null) {
                String reason = override ? "explicitly enabled" : "explicitly disabled";
                return new EnablementDecision(override, reason);
            }
            boolean def = resolveDefault(ctx.processId());
            return new EnablementDecision(def, "default");
        } catch (Exception e) {
            LOG.warnf(e, "Unexpected error in enablement decision for process=%s; falling back to default",
                    ctx.processId());
            boolean def = safeDefault(ctx.processId());
            return new EnablementDecision(def, "default (fail-safe after error)");
        }
    }

    // -------------------------------------------------------------------------
    // Mutable toggle
    // -------------------------------------------------------------------------

    /** Mark the process as enabled. Idempotent. */
    public void enable(String processId) {
        overrides.put(processId, Boolean.TRUE);
        LOG.infof("Process enabled: %s", processId);
    }

    /** Mark the process as disabled. Idempotent. */
    public void disable(String processId) {
        overrides.put(processId, Boolean.FALSE);
        LOG.infof("Process disabled: %s", processId);
    }

    /**
     * Convenience shorthand — equivalent to {@code decide(EnablementContext.of(processId)).enabled()}.
     * Use only at call sites with no richer context (e.g. {@code state()}, REST round-trip).
     * All <em>gate</em> call sites should call {@link #decide(EnablementContext)} with a full context.
     */
    public boolean isEnabled(String processId) {
        return decide(EnablementContext.of(processId)).enabled();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Resolve the default from the process bean, lazily.
     * Returns {@code true} (fail-safe) if no matching bean is found.
     */
    private boolean resolveDefault(String processId) {
        for (CognitiveProcess p : processes) {
            if (processId.equals(p.id())) {
                return p.defaultEnabled();
            }
        }
        LOG.warnf("No CognitiveProcess bean found for id=%s; defaulting to enabled", processId);
        return true;
    }

    /**
     * Same as {@link #resolveDefault} but swallows all exceptions — used in the
     * outer fail-safe catch block where we must not throw again.
     */
    private boolean safeDefault(String processId) {
        try {
            return resolveDefault(processId);
        } catch (Exception ex) {
            LOG.warnf(ex, "safeDefault also failed for process=%s; returning true", processId);
            return true;
        }
    }
}
