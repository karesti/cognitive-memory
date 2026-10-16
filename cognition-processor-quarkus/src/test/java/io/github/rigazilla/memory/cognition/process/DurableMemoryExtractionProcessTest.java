package io.github.rigazilla.memory.cognition.process;

import io.github.rigazilla.memory.cognition.enablement.ProcessEnablementService;
import io.github.rigazilla.memory.cognition.event.GrpcAdminEventClient;
import io.github.rigazilla.memory.cognition.queue.JobQueueRegistry;
import io.github.rigazilla.memory.cognition.token.TokenTracker;
import io.github.rigazilla.memory.cognition.token.TokenUsage;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link DurableMemoryExtractionProcess} enablement wiring (issue #57, step 2).
 *
 * <p>Uses {@code @QuarkusTest} so the real CDI container wires config and the
 * {@link ProcessEnablementService}. Infrastructure collaborators that open
 * network connections ({@link GrpcAdminEventClient}, {@link JobQueueRegistry})
 * are replaced with {@code @InjectMock}.
 */
@QuarkusTest
class DurableMemoryExtractionProcessTest {

    @Inject
    DurableMemoryExtractionProcess process;

    @Inject
    ProcessEnablementService enablementService;

    @Inject
    TokenTracker tokenTracker;

    @InjectMock
    GrpcAdminEventClient eventClient;

    @InjectMock
    JobQueueRegistry jobQueueRegistry;

    @BeforeEach
    void setUp() {
        ProcessEnablementService realEnablementService = (ProcessEnablementService)
                ((ClientProxy) enablementService).arc_contextualInstance();
        realEnablementService.resetOverrides();

        // Stub the stats accessor so inspect() doesn't NPE
        when(jobQueueRegistry.getStats()).thenReturn(new JobQueueRegistry.RegistryStats(0, 0, 0));
    }

    // -------------------------------------------------------------------------
    // Capability flags
    // -------------------------------------------------------------------------

    @Test
    void supportsEnableIsTrue() {
        assertTrue(process.supportsEnable());
    }

    @Test
    void supportsDisableIsTrue() {
        assertTrue(process.supportsDisable());
    }

    // -------------------------------------------------------------------------
    // state() tracks the enablement service
    // -------------------------------------------------------------------------

    @Test
    void stateIsEnabledByDefault() {
        assertEquals(ManagedProcessState.ENABLED, process.state());
    }

    @Test
    void stateIsDisabledAfterDisable() {
        process.disable();
        assertEquals(ManagedProcessState.DISABLED, process.state());
    }

    @Test
    void stateIsEnabledAfterReEnable() {
        process.disable();
        process.enable();
        assertEquals(ManagedProcessState.ENABLED, process.state());
    }

    // -------------------------------------------------------------------------
    // enable() / disable() delegate to the service
    // -------------------------------------------------------------------------

    @Test
    void disableSetsServiceFlag() {
        process.disable();
        assertFalse(enablementService.isEnabled(DurableMemoryExtractionProcess.PROCESS_ID));
    }

    @Test
    void enableSetsServiceFlag() {
        process.disable();
        process.enable();
        assertTrue(enablementService.isEnabled(DurableMemoryExtractionProcess.PROCESS_ID));
    }

    // -------------------------------------------------------------------------
    // inspect() state field reflects toggle
    // -------------------------------------------------------------------------

    @Test
    void inspectStateMatchesProcessState() {
        assertEquals(process.state(), process.inspect().state());

        process.disable();
        assertEquals(ManagedProcessState.DISABLED, process.inspect().state());
    }

    // -------------------------------------------------------------------------
    // inspect() resource tokenUsage
    // -------------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void inspectResourceTypesContainTokenUsageForExtractorAndVerifier() {
        ManagedProcessInspection inspection = process.inspect();

        java.util.Map<String, java.util.Map<String, Object>> resourceTypes =
                (java.util.Map<String, java.util.Map<String, Object>>)
                        inspection.details().get("resourceTypes");
        assertNotNull(resourceTypes, "resourceTypes must be present in inspect details");

        java.util.Map<String, Object> extractor = resourceTypes.get("extractor");
        assertNotNull(extractor, "extractor resource must be present");
        assertTrue(extractor.containsKey("tokenUsage"),
                "extractor resource must include tokenUsage");

        java.util.Map<String, Object> verifier = resourceTypes.get("verifier");
        assertNotNull(verifier, "verifier resource must be present");
        assertTrue(verifier.containsKey("tokenUsage"),
                "verifier resource must include tokenUsage");
    }

    @Test
    void inspectContainsProcessLevelTokenUsageAggregatingResources() {
        String id = DurableMemoryExtractionProcess.PROCESS_ID;
        long before = tokenTracker.processSnapshot(id).totalTokens();
        tokenTracker.record(id, "extractor", 100, 50);
        tokenTracker.record(id, "verifier", 20, 10);

        ManagedProcessInspection inspection = process.inspect();

        Object usage = inspection.details().get("tokenUsage");
        assertNotNull(usage, "process-level tokenUsage must be present");
        assertEquals(before + 180L, ((TokenUsage.Snapshot) usage).totalTokens());
    }
}
