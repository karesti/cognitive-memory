package io.github.rigazilla.memory.cognition.process;

import io.github.rigazilla.memory.cognition.enablement.ProcessEnablementService;
import io.github.rigazilla.memory.cognition.event.GrpcAdminEventClient;
import io.github.rigazilla.memory.cognition.queue.JobQueueRegistry;
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
}
