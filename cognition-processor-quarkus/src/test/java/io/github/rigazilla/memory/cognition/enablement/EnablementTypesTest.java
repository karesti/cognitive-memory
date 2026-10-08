package io.github.rigazilla.memory.cognition.enablement;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Plain JUnit 5 tests for the pure record types {@link EnablementContext} and
 * {@link EnablementDecision}. No CDI container needed.
 */
class EnablementTypesTest {

    // -------------------------------------------------------------------------
    // EnablementContext
    // -------------------------------------------------------------------------

    @Test
    void ofFactoryPopulatesProcessIdOnly() {
        EnablementContext ctx = EnablementContext.of("my-process");

        assertEquals("my-process", ctx.processId());
        assertEquals(Optional.empty(), ctx.userId());
        assertEquals(Optional.empty(), ctx.clientId());
        assertEquals(Optional.empty(), ctx.conversationId());
        assertTrue(ctx.attributes().isEmpty());
    }

    @Test
    void fullConstructorRoundTrips() {
        EnablementContext ctx = new EnablementContext(
                "p1",
                Optional.of("alice"),
                Optional.of("client-x"),
                Optional.of("conv-99"),
                Map.of("k", "v"));

        assertEquals("p1", ctx.processId());
        assertEquals(Optional.of("alice"), ctx.userId());
        assertEquals(Optional.of("client-x"), ctx.clientId());
        assertEquals(Optional.of("conv-99"), ctx.conversationId());
        assertEquals("v", ctx.attributes().get("k"));
    }

    // -------------------------------------------------------------------------
    // EnablementDecision
    // -------------------------------------------------------------------------

    @Test
    void enabledDecisionCarriesFlag() {
        EnablementDecision d = new EnablementDecision(true, "explicit");
        assertTrue(d.enabled());
        assertEquals("explicit", d.reason());
    }

    @Test
    void disabledDecisionCarriesFlag() {
        EnablementDecision d = new EnablementDecision(false, "explicitly disabled");
        assertFalse(d.enabled());
    }
}
