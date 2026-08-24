package io.github.rigazilla.memory.cognition.config;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies documented resource defaults and process overrides bind to the typed mapping.
 */
@QuarkusTest
@TestProfile(CognitionResourceConfigurationStartupTest.ResourceOverrideProfile.class)
class CognitionResourceConfigurationStartupTest {

    @Inject
    CognitionConfig cognition;

    @Test
    void documentedResourceOverride_bindsToCognitionConfig() {
        assertThat(cognition.resources().defaultResource().llm())
                .isPresent()
                .get()
                .satisfies(llm -> {
                    assertThat(llm.provider()).contains("ollama");
                    assertThat(llm.model()).contains("llama3.2");
                });
        assertThat(cognition.process())
                .containsKey("profile-context-consolidation");
        assertThat(cognition.process().get("profile-context-consolidation").llm())
                .isPresent()
                .get()
                .satisfies(llm -> assertThat(llm.temperature()).contains(0.3));
        assertThat(cognition.process().get("profile-context-consolidation").resources())
                .containsKey("consolidator");
        assertThat(cognition.process().get("profile-context-consolidation")
                .resources().get("consolidator").llm())
                .isPresent()
                .get()
                .satisfies(llm -> assertThat(llm.model()).contains("llama3.2:70b"));
    }

    public static class ResourceOverrideProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "cognition.resources.default.llm.provider", "ollama",
                    "cognition.resources.default.llm.model", "llama3.2",
                    "cognition.process.profile-context-consolidation.llm.temperature", "0.3",
                    "cognition.process.profile-context-consolidation.resources.consolidator.llm.model", "llama3.2:70b");
        }
    }
}
