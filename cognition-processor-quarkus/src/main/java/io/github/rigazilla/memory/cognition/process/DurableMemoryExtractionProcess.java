package io.github.rigazilla.memory.cognition.process;

import io.github.rigazilla.memory.cognition.event.GrpcAdminEventClient;
import io.github.rigazilla.memory.cognition.extraction.DurableMemoryExtractor;
import io.github.rigazilla.memory.cognition.queue.JobQueueRegistry;
import io.github.rigazilla.memory.cognition.resource.LlmResourceConfiguration;
import io.github.rigazilla.memory.cognition.resource.ResourceRequirements;
import io.github.rigazilla.memory.cognition.verification.DurableMemoryVerifier;
import io.quarkiverse.langchain4j.RegisterAiService;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Managed process adapter for the durable memory extraction pipeline.
 */
@ApplicationScoped
public class DurableMemoryExtractionProcess implements CognitiveProcess {

    private static final Logger LOG = Logger.getLogger(DurableMemoryExtractionProcess.class);
    public static final String PROCESS_ID = "durable-memory-extraction";

    @Inject
    GrpcAdminEventClient eventClient;

    @Inject
    JobQueueRegistry jobQueueRegistry;

    @Inject
    Config config;

    private String extractorPrompt;
    private String verifierPrompt;

    @PostConstruct
    void init() {
        this.extractorPrompt = loadPrompt("prompts/durable-extractor-system.md");
        this.verifierPrompt = loadPrompt("prompts/durable-verifier-system.md");
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
        return "Durable Memory Extraction";
    }

    @Override
    public String description() {
        return "Event-driven extraction, verification, and writing of durable memories";
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
    public ManagedProcessInspection inspect() {
        JobQueueRegistry.RegistryStats stats = jobQueueRegistry.getStats();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("eventStreamConnected", eventClient.isConnected());
        details.put("eventsAccepted", eventClient.getEventCount());
        details.put("activeWindows", eventClient.getWindowCount());
        details.put("totalQueues", stats.totalQueues());
        details.put("activeQueues", stats.activeQueues());
        details.put("pendingJobs", stats.pendingJobs());
        
        // Add resource type information with prompts
        ResourceRequirements requirements = getResourceRequirements();
        if (requirements != null) {
            Map<String, Map<String, String>> resourceTypes = new LinkedHashMap<>();
            requirements.getAllResources().forEach((name, resourceConfig) -> {
                Map<String, String> resourceInfo = new LinkedHashMap<>();
                resourceInfo.put("type", resourceConfig.getType().name());
                
                // Add LLM details from the actual AI service annotation
                if (resourceConfig.getType() == io.github.rigazilla.memory.cognition.resource.ResourceType.LLM) {
                    Class<?> aiServiceClass = switch (name) {
                        case "extractor" -> DurableMemoryExtractor.class;
                        case "verifier" -> DurableMemoryVerifier.class;
                        default -> null;
                    };
                    if (aiServiceClass != null) {
                        addLlmDetails(resourceInfo, aiServiceClass);
                    }
                    
                    String promptContent = switch (name) {
                        case "extractor" -> extractorPrompt;
                        case "verifier" -> verifierPrompt;
                        default -> null;
                    };
                    if (promptContent != null) {
                        resourceInfo.put("prompt", promptContent);
                    }
                }
                
                resourceTypes.put(name, resourceInfo);
            });
            details.put("resourceTypes", resourceTypes);
        }

        return new ManagedProcessInspection(
            id(),
            displayName(),
            description(),
            state(),
            details
        );
    }

    @Override
    public void start() {
        LOG.infof("Start requested for process %s", id());
        eventClient.startIfNeeded();
    }

    /**
     * Populate LLM resource info by reading the modelName from the @RegisterAiService annotation
     * and resolving the actual provider, model, and endpoint from Quarkus config.
     */
    private void addLlmDetails(Map<String, String> resourceInfo, Class<?> aiServiceClass) {
        RegisterAiService annotation = aiServiceClass.getAnnotation(RegisterAiService.class);
        if (annotation == null) {
            return;
        }
        String modelName = annotation.modelName();
        String provider = config.getOptionalValue(
            "quarkus.langchain4j." + modelName + ".chat-model.provider", String.class).orElse("unknown");
        resourceInfo.put("provider", provider);
        resourceInfo.put("modelName", modelName);

        if ("ollama".equals(provider)) {
            resourceInfo.put("model", config.getOptionalValue(
                "quarkus.langchain4j.ollama." + modelName + ".chat-model.model-id", String.class).orElse("default"));
            resourceInfo.put("endpoint", config.getOptionalValue(
                "quarkus.langchain4j.ollama." + modelName + ".base-url",
                String.class).orElse("http://localhost:11434"));
        } else {
            resourceInfo.put("model", config.getOptionalValue(
                "quarkus.langchain4j.openai." + modelName + ".chat-model.model-name", String.class).orElse("unknown"));
            resourceInfo.put("endpoint", config.getOptionalValue(
                "quarkus.langchain4j.openai." + modelName + ".base-url", String.class).orElse("unknown"));
        }
    }

    @Override
    public ResourceRequirements getResourceRequirements() {
        // Declare LLM resources for extraction and verification
        // Both use the same configuration for now (can be overridden in application.properties)
        return ResourceRequirements.builder()
            .llm("extractor", createDefaultLlmConfig())
            .llm("verifier", createDefaultLlmConfig())
            .build();
    }

    private LlmResourceConfiguration createDefaultLlmConfig() {
        // Default configuration - will be overridden by resolver from application.properties
        return new LlmResourceConfiguration() {
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
                return 4096;
            }

            @Override
            public Optional<String> apiKey() {
                return Optional.empty();
            }

            @Override
            public Duration timeout() {
                return Duration.ofSeconds(120);
            }

            @Override
            public Map<String, String> customProperties() {
                return Map.of();
            }
        };
    }
}
