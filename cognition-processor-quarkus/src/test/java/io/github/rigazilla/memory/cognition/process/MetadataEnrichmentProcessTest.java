package io.github.rigazilla.memory.cognition.process;

import io.github.rigazilla.memory.cognition.metadata.MetadataEnrichmentService;
import io.github.rigazilla.memory.cognition.token.TokenTracker;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link MetadataEnrichmentProcess}.
 *
 * <p>Covers the {@link io.github.rigazilla.memory.cognition.process.CognitiveProcess} contract
 * and the {@link MetadataEnrichmentProcess#inspect()} payload wiring.
 * No CDI container needed — dependencies are injected directly.
 * {@link MetadataEnrichmentService} is mocked so no gRPC channel is required.
 */
class MetadataEnrichmentProcessTest {

    private MetadataEnrichmentProcess process;
    private MetadataEnrichmentService mockService;

    @BeforeEach
    void setUp() {
        // Mock the service so no gRPC channel is required
        mockService = mock(MetadataEnrichmentService.class);
        when(mockService.getStatus()).thenReturn("idle");
        when(mockService.getProcessed()).thenReturn(0);
        when(mockService.getEnriched()).thenReturn(0);
        when(mockService.getErrors()).thenReturn(0);
        when(mockService.getLastRunTime()).thenReturn(null);

        // Mock Config so addLlmDetails() does not fail in inspect()
        Config mockConfig = mock(Config.class);
        when(mockConfig.getOptionalValue(any(), eq(String.class))).thenReturn(Optional.empty());

        process = new MetadataEnrichmentProcess();
        process.enrichmentService = mockService;
        process.config = mockConfig;
        process.tokenTracker = new TokenTracker();
    }

    // -------------------------------------------------------------------------
    // CognitiveProcess contract — constant/simple-return methods
    // -------------------------------------------------------------------------

    @Test
    void idReturnsExpectedProcessId() {
        assertEquals("metadata-enrichment", process.id());
    }

    @Test
    void idMatchesPublishedConstant() {
        assertEquals(MetadataEnrichmentProcess.PROCESS_ID, process.id());
    }

    @Test
    void displayNameIsNonBlank() {
        assertNotNull(process.displayName());
        assertFalse(process.displayName().isBlank(), "displayName must not be blank");
    }

    @Test
    void descriptionIsNonBlank() {
        assertNotNull(process.description());
        assertFalse(process.description().isBlank(), "description must not be blank");
    }

    @Test
    void supportsStartIsTrue() {
        assertTrue(process.supportsStart());
    }

    @Test
    void supportsEnableIsFalse() {
        assertFalse(process.supportsEnable());
    }

    @Test
    void supportsDisableIsFalse() {
        assertFalse(process.supportsDisable());
    }

    @Test
    void stateIsEnabled() {
        assertEquals(ManagedProcessState.ENABLED, process.state());
    }

    // -------------------------------------------------------------------------
    // inspect() payload wiring
    // -------------------------------------------------------------------------

    @Test
    void inspectReturnsAllRequiredKeys() {
        ManagedProcessInspection result = process.inspect();

        assertNotNull(result, "inspect() must not return null");
        assertNotNull(result.details(), "details map must not be null");

        assertTrue(result.details().containsKey("status"),
                "inspect payload must contain 'status'");
        assertTrue(result.details().containsKey("processed"),
                "inspect payload must contain 'processed'");
        assertTrue(result.details().containsKey("enriched"),
                "inspect payload must contain 'enriched'");
        assertTrue(result.details().containsKey("errors"),
                "inspect payload must contain 'errors'");
        assertTrue(result.details().containsKey("lastRunTime"),
                "inspect payload must contain 'lastRunTime'");
    }

    @Test
    void inspectValuesMatchServiceAccessors() {
        when(mockService.getProcessed()).thenReturn(10);
        when(mockService.getEnriched()).thenReturn(7);
        when(mockService.getErrors()).thenReturn(2);
        when(mockService.getStatus()).thenReturn("completed");

        ManagedProcessInspection result = process.inspect();

        assertEquals(10, result.details().get("processed"),
                "inspect 'processed' must reflect enrichmentService.getProcessed()");
        assertEquals(7, result.details().get("enriched"),
                "inspect 'enriched' must reflect enrichmentService.getEnriched()");
        assertEquals(2, result.details().get("errors"),
                "inspect 'errors' must reflect enrichmentService.getErrors()");
        assertEquals("completed", result.details().get("status"),
                "inspect 'status' must reflect enrichmentService.getStatus()");
    }

    @Test
    void inspectLastRunTimeIsNeverWhenServiceReturnsNull() {
        when(mockService.getLastRunTime()).thenReturn(null);

        ManagedProcessInspection result = process.inspect();

        assertEquals("never", result.details().get("lastRunTime"),
                "lastRunTime must be 'never' when the service returns null");
    }

    @Test
    void inspectLastRunTimeIsIsoStringAfterRun() {
        Instant runTime = Instant.parse("2025-06-10T13:30:00Z");
        when(mockService.getLastRunTime()).thenReturn(runTime);

        ManagedProcessInspection result = process.inspect();

        assertEquals("2025-06-10T13:30:00Z", result.details().get("lastRunTime"),
                "lastRunTime must surface as ISO-8601 string when the service has a value");
    }

    @Test
    void inspectIdAndDisplayNameMatchProcessContract() {
        ManagedProcessInspection result = process.inspect();
        assertEquals(process.id(), result.id());
        assertEquals(process.displayName(), result.displayName());
    }

    @Test
    void inspectDescriptionMatchesProcessContract() {
        ManagedProcessInspection result = process.inspect();
        assertEquals(process.description(), result.description());
    }

    @Test
    void inspectStateIsEnabled() {
        ManagedProcessInspection result = process.inspect();
        assertEquals(ManagedProcessState.ENABLED, result.state());
    }

    @Test
    void inspectIncludesResourceTypesSection() {
        ManagedProcessInspection result = process.inspect();
        assertTrue(result.details().containsKey("resourceTypes"),
                "inspect payload must include 'resourceTypes' from getResourceRequirements()");
    }

    @Test
    @SuppressWarnings("unchecked")
    void inspectExtractorResourceContainsTokenUsage() {
        ManagedProcessInspection result = process.inspect();

        Map<String, Map<String, Object>> resourceTypes =
                (Map<String, Map<String, Object>>) result.details().get("resourceTypes");
        assertNotNull(resourceTypes, "resourceTypes must not be null");

        Map<String, Object> extractor = resourceTypes.get("extractor");
        assertNotNull(extractor, "extractor resource must be present");
        assertTrue(extractor.containsKey("tokenUsage"),
                "extractor resource must expose 'tokenUsage'");
    }

    // -------------------------------------------------------------------------
    // start() delegates to enrichmentService.startEnrichmentAsync()
    // -------------------------------------------------------------------------

    @Test
    void startDelegatesToStartEnrichmentAsync() {
        process.start();
        verify(mockService).startEnrichmentAsync(null);
    }

    @Test
    void startDoesNotThrow() {
        assertDoesNotThrow(() -> process.start());
    }

    @Test
    void startWithPrefixParamPassesPrefixToService() {
        List<String> prefix = List.of("user", "alice");
        process.start(Map.of("namespacePrefix", prefix));
        verify(mockService).startEnrichmentAsync(prefix);
    }

    @Test
    void startWithEmptyParamsCallsServiceWithNullPrefix() {
        process.start(Map.of());
        verify(mockService).startEnrichmentAsync(null);
    }

    @Test
    void noArgStartDelegatesToParamStart() {
        // no-arg start() must ultimately call startEnrichmentAsync(null), not the no-arg overload
        process.start();
        verify(mockService).startEnrichmentAsync(null);
        verify(mockService, never()).startEnrichmentAsync();
    }

    // -------------------------------------------------------------------------
    // enable() / disable() — not supported
    // -------------------------------------------------------------------------

    @Test
    void enableThrowsUnsupportedOperationException() {
        assertThrows(UnsupportedOperationException.class, () -> process.enable());
    }

    @Test
    void disableThrowsUnsupportedOperationException() {
        assertThrows(UnsupportedOperationException.class, () -> process.disable());
    }

    // -------------------------------------------------------------------------
    // getResourceRequirements()
    // -------------------------------------------------------------------------

    @Test
    void getResourceRequirementsIsNotNull() {
        assertNotNull(process.getResourceRequirements(),
                "getResourceRequirements() must return a non-null instance");
    }

    @Test
    void getResourceRequirementsHasExtractorEntry() {
        assertTrue(process.getResourceRequirements().getAllResources().containsKey("extractor"),
                "resource requirements must declare an 'extractor' LLM resource");
    }
}
