package io.github.rigazilla.memory.cognition.enablement;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ProcessEnablementService}.
 *
 * <p>Uses {@code @QuarkusTest} so the real CDI container wires the service and
 * discovers all {@link CognitiveProcess} beans — the same environment as production.
 */
@QuarkusTest
class ProcessEnablementServiceTest {

    @Inject
    ProcessEnablementService service;

    @BeforeEach
    void setUp() {
        // Unwrap CDI proxy to reset the overrides map between tests
        ProcessEnablementService realService = (ProcessEnablementService)
                ((io.quarkus.arc.ClientProxy) service).arc_contextualInstance();
        realService.resetOverrides();
    }

    // -------------------------------------------------------------------------
    // Default behaviour (no explicit toggle set)
    // -------------------------------------------------------------------------

    @Test
    void defaultIsEnabledForKnownProcess() {
        // All v1 processes have defaultEnabled()=true
        EnablementDecision d = service.decide(EnablementContext.of("durable-memory-extraction"));
        assertTrue(d.enabled(), "Known process must be enabled by default");
        assertEquals("default", d.reason());
    }

    @Test
    void defaultIsEnabledForUnknownProcess() {
        // Unknown process id: no bean found — fail-safe returns true
        EnablementDecision d = service.decide(EnablementContext.of("does-not-exist"));
        assertTrue(d.enabled(), "Unknown process must default to enabled (fail-safe)");
    }

    @Test
    void isEnabledConvenienceMatchesDecide() {
        boolean fromDecide = service.decide(EnablementContext.of("metadata-enrichment")).enabled();
        boolean fromIsEnabled = service.isEnabled("metadata-enrichment");
        assertEquals(fromDecide, fromIsEnabled);
    }

    // -------------------------------------------------------------------------
    // Toggle: disable then re-enable
    // -------------------------------------------------------------------------

    @Test
    void disableMakesDecideReturnFalse() {
        service.disable("metadata-enrichment");
        EnablementDecision d = service.decide(EnablementContext.of("metadata-enrichment"));
        assertFalse(d.enabled());
        assertEquals("explicitly disabled", d.reason());
    }

    @Test
    void enableAfterDisableRestoresEnabled() {
        service.disable("metadata-enrichment");
        service.enable("metadata-enrichment");
        assertTrue(service.isEnabled("metadata-enrichment"));
    }

    @Test
    void disableIsIdempotent() {
        service.disable("temporal-metadata-enrichment");
        service.disable("temporal-metadata-enrichment");
        assertFalse(service.isEnabled("temporal-metadata-enrichment"));
    }

    @Test
    void enableIsIdempotent() {
        service.enable("temporal-metadata-enrichment");
        service.enable("temporal-metadata-enrichment");
        assertTrue(service.isEnabled("temporal-metadata-enrichment"));
    }

    // -------------------------------------------------------------------------
    // Context fields are passed through (extension-readiness guard)
    // -------------------------------------------------------------------------

    @Test
    void decideWithFullContextUsesProcessId() {
        service.disable("contradiction-resolution");

        EnablementContext ctx = new EnablementContext(
                "contradiction-resolution",
                Optional.of("alice"),
                Optional.of("client-x"),
                Optional.of("conv-99"),
                java.util.Map.of());

        assertFalse(service.decide(ctx).enabled(),
                "v1 policy must key on processId regardless of other context fields");
    }

    // -------------------------------------------------------------------------
    // Toggle does not bleed across process ids
    // -------------------------------------------------------------------------

    @Test
    void disablingOneProcessDoesNotAffectAnother() {
        service.disable("metadata-enrichment");
        assertTrue(service.isEnabled("durable-memory-extraction"),
                "Disabling one process must not affect another");
    }
}
