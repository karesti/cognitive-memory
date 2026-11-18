package io.github.rigazilla.memory.cognition.process;

import io.github.rigazilla.memory.cognition.config.CognitionConfig;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for DurableMemoryExtractionProcess configuration, specifically the verifier enable/disable feature.
 */
@QuarkusTest
class DurableMemoryExtractionProcessConfigTest {

    @Inject
    DurableMemoryExtractionProcess process;

    @Inject
    CognitionConfig cognitionConfig;

    @BeforeEach
    void setUp() {
        // Reset verifier to enabled state before each test
        // This is necessary because @QuarkusTest reuses the same application instance
        process.configure(Map.of("verifierEnabled", true));
    }

    @Test
    void testVerifierEnabledByDefault() {
        // Verify the default from application.properties is true
        assertTrue(cognitionConfig.durableMemoryExtraction().verifier().enabled(),
            "Verifier should be enabled by default in application.properties");
        
        // Verify the process reflects this default
        assertTrue(process.isVerifierEnabled(),
            "Process should have verifier enabled by default");
    }

    @Test
    void testConfigureVerifierDisabled() {
        // Initially enabled
        assertTrue(process.isVerifierEnabled());

        // Disable via runtime configuration
        Map<String, Object> config = Map.of("verifierEnabled", false);
        process.configure(config);

        // Verify disabled
        assertFalse(process.isVerifierEnabled(),
            "Verifier should be disabled after configuration");
    }

    @Test
    void testConfigureVerifierEnabled() {
        // Disable first
        process.configure(Map.of("verifierEnabled", false));
        assertFalse(process.isVerifierEnabled());

        // Re-enable via runtime configuration
        Map<String, Object> config = Map.of("verifierEnabled", true);
        process.configure(config);

        // Verify enabled
        assertTrue(process.isVerifierEnabled(),
            "Verifier should be enabled after configuration");
    }

    @Test
    void testConfigureWithInvalidValueType() {
        // Initially enabled
        assertTrue(process.isVerifierEnabled());

        // Try to configure with invalid type (String instead of Boolean)
        Map<String, Object> config = Map.of("verifierEnabled", "false");
        assertThrows(IllegalArgumentException.class, () -> process.configure(config));

        // Should remain enabled (invalid config rejected)
        assertTrue(process.isVerifierEnabled(),
            "Verifier should remain enabled when invalid config value is provided");
    }

    @Test
    void testConfigureWithNullValue() {
        // Initially enabled
        assertTrue(process.isVerifierEnabled());

        Map<String, Object> config = new HashMap<>();
        config.put("verifierEnabled", null);
        assertThrows(IllegalArgumentException.class, () -> process.configure(config));

        assertTrue(process.isVerifierEnabled(),
            "Verifier should remain enabled when null config value is provided");
    }

    @Test
    void testConfigureWithEmptyMap() {
        // Initially enabled
        assertTrue(process.isVerifierEnabled());

        // Configure with empty map
        process.configure(Map.of());

        // Should remain enabled (no change)
        assertTrue(process.isVerifierEnabled(),
            "Verifier should remain enabled when empty config is provided");
    }

    @Test
    void testConfigureWithUnrelatedKeys() {
        // Initially enabled
        assertTrue(process.isVerifierEnabled());

        // Configure with unrelated keys
        Map<String, Object> config = Map.of(
            "someOtherKey", "value",
            "anotherKey", 123
        );
        process.configure(config);

        // Should remain enabled (unrelated keys ignored)
        assertTrue(process.isVerifierEnabled(),
            "Verifier should remain enabled when unrelated config keys are provided");
    }

    @Test
    void testInspectShowsVerifierStatus() {
        // Test with verifier enabled
        process.configure(Map.of("verifierEnabled", true));
        ManagedProcessInspection inspection = process.inspect();
        
        assertTrue((Boolean) inspection.details().get("verifierEnabled"),
            "Inspection should show verifier as enabled");

        // Test with verifier disabled
        process.configure(Map.of("verifierEnabled", false));
        inspection = process.inspect();
        
        assertFalse((Boolean) inspection.details().get("verifierEnabled"),
            "Inspection should show verifier as disabled");
    }

    @Test
    void testMultipleConfigurationChanges() {
        // Start enabled
        assertTrue(process.isVerifierEnabled());

        // Disable
        process.configure(Map.of("verifierEnabled", false));
        assertFalse(process.isVerifierEnabled());

        // Enable
        process.configure(Map.of("verifierEnabled", true));
        assertTrue(process.isVerifierEnabled());

        // Disable again
        process.configure(Map.of("verifierEnabled", false));
        assertFalse(process.isVerifierEnabled());

        // Enable again
        process.configure(Map.of("verifierEnabled", true));
        assertTrue(process.isVerifierEnabled());
    }

    @Test
    void testDescriptionIncludesVerifierStatus() {
        // Test with verifier enabled
        process.configure(Map.of("verifierEnabled", true));
        String description = process.description();
        assertTrue(description.contains("with verification"),
            "Description should indicate verification is enabled");

        // Test with verifier disabled
        process.configure(Map.of("verifierEnabled", false));
        description = process.description();
        assertTrue(description.contains("without verification"),
            "Description should indicate verification is disabled");
    }
}
