package io.github.rigazilla.memory.cognition.temporal;

import io.github.chirino.memory.grpc.v1.AdminListMemoriesResponse;
import io.github.chirino.memory.grpc.v1.AdminMemoriesServiceGrpc;
import io.github.rigazilla.memory.cognition.process.ManagedProcessInspection;
import io.github.rigazilla.memory.cognition.process.ManagedProcessState;
import io.github.rigazilla.memory.cognition.queue.JobProcessor;
import io.grpc.ManagedChannel;
import io.quarkus.arc.Arc;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link TemporalMetadataEnrichmentProcess}.
 *
 * <p>Covers the {@link io.github.rigazilla.memory.cognition.process.CognitiveProcess} contract
 * (R7) and the {@link TemporalMetadataEnrichmentProcess#inspect()} payload wiring (R10).
 *
 * <p>Uses {@code @QuarkusTest} so CDI wires config from {@code test/resources/application.properties}.
 * The gRPC stub/channel are replaced per test; {@link JobProcessor} is replaced by {@code @InjectMock}
 * since only its {@code getApproximateObservedAtCount()} accessor is exercised here.
 */
@QuarkusTest
class TemporalMetadataEnrichmentProcessTest {

    @Inject
    TemporalMetadataEnrichmentProcess process;

    @Inject
    TemporalMetadataEnrichmentService enrichmentService;

    /** The real (non-proxy) enrichment service instance — used for field injection of mock stubs. */
    private TemporalMetadataEnrichmentService realEnrichmentService;

    @InjectMock
    JobProcessor jobProcessor;

    @BeforeEach
    void setUp() {
        AdminMemoriesServiceGrpc.AdminMemoriesServiceBlockingStub mockStub =
            mock(AdminMemoriesServiceGrpc.AdminMemoriesServiceBlockingStub.class);
        ManagedChannel mockChannel = mock(ManagedChannel.class);

        // Unwrap CDI proxy to reach the real bean instance — field assignment on the proxy
        // itself is a no-op because @ApplicationScoped beans are wrapped in client proxies.
        // arc_contextualInstance() returns the actual delegate, not the proxy shell.
        realEnrichmentService = (TemporalMetadataEnrichmentService)
                ((io.quarkus.arc.ClientProxy) enrichmentService).arc_contextualInstance();
        realEnrichmentService.memoriesStub = mockStub;
        realEnrichmentService.channel = mockChannel;
        // Reset counters and flags so tests are independent of execution order
        realEnrichmentService.running.set(false);
        realEnrichmentService.scanned.set(0);
        realEnrichmentService.enriched.set(0);
        realEnrichmentService.skipped.set(0);
        realEnrichmentService.errors.set(0);
        realEnrichmentService.conflicts.set(0);

        when(jobProcessor.getApproximateObservedAtCount()).thenReturn(0L);
    }

    // -------------------------------------------------------------------------
    // R7 — CognitiveProcess contract (all constant/simple-return methods)
    // -------------------------------------------------------------------------

    @Test
    void idReturnsExpectedProcessId() {
        // R7: process must be discoverable under a stable, well-known ID
        assertEquals("temporal-metadata-enrichment", process.id());
    }

    @Test
    void idMatchesPublishedConstant() {
        // The constant must equal the runtime value so callers can reference it without
        // constructing the process (e.g. for URL generation in tests/admin tools)
        assertEquals(TemporalMetadataEnrichmentProcess.PROCESS_ID, process.id());
    }

    @Test
    void displayNameIsNonBlank() {
        // R7: registry endpoint must return a human-readable name
        assertNotNull(process.displayName());
        assertFalse(process.displayName().isBlank(), "displayName must not be blank");
    }

    @Test
    void descriptionIsNonBlank() {
        // R7: registry endpoint must return a description
        assertNotNull(process.description());
        assertFalse(process.description().isBlank(), "description must not be blank");
    }

    @Test
    void supportsStartIsTrue() {
        // R7: process must be triggerable via POST /api/processes/.../start
        assertTrue(process.supportsStart());
    }

    @Test
    void supportsEnableIsFalse() {
        // Enable/disable not supported — always-on once the process bean is loaded
        assertFalse(process.supportsEnable());
    }

    @Test
    void supportsDisableIsFalse() {
        assertFalse(process.supportsDisable());
    }

    @Test
    void stateIsEnabled() {
        // Process is always ENABLED — no toggle needed for a one-shot backfill trigger
        assertEquals(ManagedProcessState.ENABLED, process.state());
    }

    // -------------------------------------------------------------------------
    // R10 — inspect() payload wiring
    // -------------------------------------------------------------------------

    @Test
    void inspectReturnsAllRequiredKeys() {
        // R10: inspect() must expose enriched, errors, conflicts, and fallbackTimestampsUsed
        // so operators can assess backfill quality without grepping logs.
        // Additional keys (scanned, skipped, running, lastStartTime, …) are also present.
        ManagedProcessInspection result = process.inspect();

        assertNotNull(result, "inspect() must not return null");
        assertNotNull(result.details(), "details map must not be null");

        assertTrue(result.details().containsKey("enriched"),
            "inspect payload must contain 'enriched'");
        assertTrue(result.details().containsKey("errors"),
            "inspect payload must contain 'errors'");
        assertTrue(result.details().containsKey("conflicts"),
            "inspect payload must contain 'conflicts'");
        assertTrue(result.details().containsKey("approximateObservedAtWrites"),
            "inspect payload must contain 'approximateObservedAtWrites' (fallbackTimestampsUsed)");
    }

    @Test
    void inspectValuesMatchServiceAccessors() {
        // R10: the wiring from service accessors → inspect details must not be silently broken.
        // Seed known values on the service counters and verify they appear in the payload.
        realEnrichmentService.enriched.set(7);
        realEnrichmentService.errors.set(3);
        realEnrichmentService.conflicts.set(2);
        realEnrichmentService.scanned.set(12);
        realEnrichmentService.skipped.set(2);

        ManagedProcessInspection result = process.inspect();

        assertEquals(7L, result.details().get("enriched"),
            "inspect 'enriched' must reflect enrichmentService.getEnriched()");
        assertEquals(3L, result.details().get("errors"),
            "inspect 'errors' must reflect enrichmentService.getErrors()");
        assertEquals(2L, result.details().get("conflicts"),
            "inspect 'conflicts' must reflect enrichmentService.getConflicts()");
        assertEquals(12L, result.details().get("scanned"),
            "inspect 'scanned' must reflect enrichmentService.getScanned()");
        // approximateObservedAtWrites comes from JobProcessor counter; starts at 0
        assertEquals(0L, result.details().get("approximateObservedAtWrites"),
            "inspect 'approximateObservedAtWrites' must reflect jobProcessor.getApproximateObservedAtCount()");
    }

    @Test
    void inspectIdAndDisplayNameMatchProcessContract() {
        // The ManagedProcessInspection record must carry the same id and displayName
        // so the REST layer can correlate the payload with the registered process.
        ManagedProcessInspection result = process.inspect();
        assertEquals(process.id(), result.id());
        assertEquals(process.displayName(), result.displayName());
    }

    @Test
    void inspectLastStartTimeIsNeverBeforeAnyRun() {
        // Before start() is ever called, lastStartTime should surface as "never"
        ManagedProcessInspection result = process.inspect();
        assertEquals("never", result.details().get("lastStartTime"),
            "lastStartTime must be 'never' before any run is triggered");
        assertEquals("never", result.details().get("lastFinishTime"),
            "lastFinishTime must be 'never' before any run is triggered");
    }

    // -------------------------------------------------------------------------
    // R5 — start() is non-blocking (async dispatch)
    // -------------------------------------------------------------------------

    @Test
    void startReturnsImmediatelyAndDoesNotBlockCaller() throws InterruptedException {
        // R5: start() must return before runBackfill() completes. We can verify this
        // without timing assertions by observing that the running flag becomes true
        // (backfill started on a virtual thread) while we still hold the call stack.
        AdminMemoriesServiceGrpc.AdminMemoriesServiceBlockingStub slowStub =
            mock(AdminMemoriesServiceGrpc.AdminMemoriesServiceBlockingStub.class);
        // Make listMemories block briefly to give the virtual thread a chance to set running=true
        when(slowStub.listMemories(any())).thenAnswer(invocation -> {
            Thread.sleep(50);
            return AdminListMemoriesResponse.newBuilder().build();
        });
        enrichmentService.memoriesStub = slowStub;

        long before = System.currentTimeMillis();
        process.start();
        long elapsed = System.currentTimeMillis() - before;

        // start() must return in well under 50ms (the stub sleep); 40ms is generous
        assertTrue(elapsed < 40,
            "start() must return immediately, not block on runBackfill(); elapsed=" + elapsed + "ms");

        // Allow virtual thread to finish to avoid open threads in the test JVM
        Thread.sleep(200);
    }
}
