package io.github.rigazilla.memory.cognition.process;

import io.github.rigazilla.memory.cognition.metadata.MetadataEnrichmentService;
import io.github.rigazilla.memory.cognition.metadata.MetadataExtractor;
import io.github.rigazilla.memory.cognition.resource.LlmResourceConfiguration;
import io.github.rigazilla.memory.cognition.resource.ResourceRequirements;
import io.github.rigazilla.memory.cognition.resource.ResourceType;
import io.quarkiverse.langchain4j.RegisterAiService;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Managed CognitiveProcess adapter for metadata enrichment.
 * Automatically registered via CDI and discoverable at /api/processes.
 * Supports start and inspect operations.
 */
@ApplicationScoped
public class MetadataEnrichmentProcess implements CognitiveProcess {

    private static final Logger LOG = Logger.getLogger(MetadataEnrichmentProcess.class);
    public static final String PROCESS_ID = "metadata-enrichment";

    @Inject
    MetadataEnrichmentService enrichmentService;

    @Inject
    Config config;

    private String extractorPrompt;

    @PostConstruct
    void init() {
        this.extractorPrompt = loadPrompt("prompts/metadata-extractor-system.md");
    }

    private String loadPrompt(String promptPath) {
        try (var stream = getClass().getClassLoader().getResourceAsStream(promptPath)) {
            if (stream == null) {
                LOG.warnf("Prompt resource not found on classpath: %s", promptPath);
                return "Prompt resource not found: " + promptPath;
            }
            return new String(stream.readAllBytes());
        } catch (Exception e) {
            LOG.errorf(e, "Failed to load prompt from classpath resource: %s", promptPath);
            return "Error loading prompt: " + promptPath;
        }
    }

    @Override
    public String id() {
        return PROCESS_ID;
    }

    @Override
    public String displayName() {
        return "Metadata Enrichment";
    }

    @Override
    public String description() {
        return "Traverses existing memories and enriches them with entity and topic metadata using LLM extraction";
    }

    @Override
    public boolean supportsStart() {
        return true;
    }

    @Override
    public boolean supportsEnable() {
        return false;
    }

    @Override
    public boolean supportsDisable() {
        return false;
    }

    @Override
    public ManagedProcessState state() {
        return ManagedProcessState.ENABLED;
    }

    @Override
    public void start() {
        start(Map.of());
    }

    @Override
    public void start(Map<String, List<String>> params) {
        List<String> prefix = params.get("namespacePrefix");
        LOG.infof("Start requested for process %s (namespacePrefix=%s)", id(), prefix);
        enrichmentService.startEnrichmentAsync(prefix);
    }

    @Override
    public ManagedProcessInspection inspect() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("status", enrichmentService.getStatus());
        details.put("processed", enrichmentService.getProcessed());
        details.put("enriched", enrichmentService.getEnriched());
        details.put("errors", enrichmentService.getErrors());
        details.put("lastRunTime",
                enrichmentService.getLastRunTime() != null
                        ? enrichmentService.getLastRunTime().toString()
                        : "never");

        ResourceRequirements requirements = getResourceRequirements();
        if (requirements != null) {
            Map<String, Map<String, String>> resourceTypes = new LinkedHashMap<>();
            requirements.getAllResources().forEach((name, resourceConfig) -> {
                Map<String, String> resourceInfo = new LinkedHashMap<>();
                resourceInfo.put("type", resourceConfig.getType().name());
                if (resourceConfig.getType() == ResourceType.LLM) {
                    addLlmDetails(resourceInfo, MetadataExtractor.class);
                    resourceInfo.put("prompt", this.extractorPrompt);
                }
                resourceTypes.put(name, resourceInfo);
            });
            details.put("resourceTypes", resourceTypes);
        }

        return new ManagedProcessInspection(id(), displayName(), description(), state(), details);
    }

    @Override
    public ResourceRequirements getResourceRequirements() {
        return ResourceRequirements.builder()
                .llm("extractor", new DefaultExtractorLlmConfig())
                .build();
    }

    private static class DefaultExtractorLlmConfig implements LlmResourceConfiguration {
        @Override
        public String provider() {
            return "ollama";
        }

        @Override
        public String model() {
            return "llama3.2";
        }

        @Override
        public Double temperature() {
            return 0.1;
        }

        @Override
        public Integer maxTokens() {
            return 2048;
        }

        @Override
        public Optional<String> apiKey() {
            return Optional.empty();
        }

        @Override
        public Duration timeout() {
            return Duration.ofSeconds(60);
        }

        @Override
        public Map<String, String> customProperties() {
            return Map.of();
        }
    }

    private void addLlmDetails(Map<String, String> resourceInfo, Class<?> aiServiceClass) {
        RegisterAiService annotation = aiServiceClass.getAnnotation(RegisterAiService.class);
        if (annotation == null) {
            return;
        }
        String modelName = annotation.modelName();
        String provider = config.getOptionalValue(
                "quarkus.langchain4j." + modelName + ".chat-model.provider", String.class)
                .orElse("unknown");
        resourceInfo.put("provider", provider);
        resourceInfo.put("modelName", modelName);
        if ("ollama".equals(provider)) {
            resourceInfo.put("model", config.getOptionalValue(
                    "quarkus.langchain4j.ollama." + modelName + ".chat-model.model-id", String.class)
                    .orElse("default"));
            resourceInfo.put("endpoint", config.getOptionalValue(
                    "quarkus.langchain4j.ollama." + modelName + ".base-url", String.class)
                    .orElse("http://localhost:11434"));
        } else {
            resourceInfo.put("model", config.getOptionalValue(
                    "quarkus.langchain4j.openai." + modelName + ".chat-model.model-name", String.class)
                    .orElse("unknown"));
            resourceInfo.put("endpoint", config.getOptionalValue(
                    "quarkus.langchain4j.openai." + modelName + ".base-url", String.class)
                    .orElse("unknown"));
        }
    }
}
