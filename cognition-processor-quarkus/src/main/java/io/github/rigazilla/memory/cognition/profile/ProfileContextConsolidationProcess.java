package io.github.rigazilla.memory.cognition.profile;

import io.github.rigazilla.memory.cognition.process.CognitiveProcess;
import io.github.rigazilla.memory.cognition.process.ManagedProcessInspection;
import io.github.rigazilla.memory.cognition.process.ManagedProcessState;
import io.github.rigazilla.memory.cognition.resource.LlmResourceConfiguration;
import io.github.rigazilla.memory.cognition.resource.ResourceRequirements;
import io.quarkiverse.langchain4j.RegisterAiService;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Managed process for profile context consolidation.
 * Phase 0: Manual trigger only, no automatic scheduling.
 */
@ApplicationScoped
public class ProfileContextConsolidationProcess implements CognitiveProcess {
    
    private static final Logger LOG = Logger.getLogger(ProfileContextConsolidationProcess.class);
    public static final String PROCESS_ID = "profile-context-consolidation";
    
    @Inject
    ProfileContextService profileContextService;

    @Inject
    Config config;

    private String consolidatorPrompt;

    @PostConstruct
    void init() {
        this.consolidatorPrompt = loadPrompt("prompts/profile-consolidator-system.md");
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
    
    private final AtomicReference<Instant> lastRunTime = new AtomicReference<>();
    private final AtomicReference<String> lastRunStatus = new AtomicReference<>("never_run");
    private final AtomicReference<String> lastRunUserId = new AtomicReference<>();
    
    @Override
    public String id() {
        return PROCESS_ID;
    }
    
    @Override
    public String displayName() {
        return "Profile Context Consolidation";
    }
    
    @Override
    public String description() {
        return "Consolidates user memories into profile snapshots (manual trigger only in Phase 0)";
    }
    
    @Override
    public boolean supportsStart() {
        return false;  // No automatic start in Phase 0
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
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("mode", "manual_trigger");
        details.put("lastRunTime", lastRunTime.get() != null ? lastRunTime.get().toString() : "never");
        details.put("lastRunStatus", lastRunStatus.get());
        details.put("lastRunUserId", lastRunUserId.get() != null ? lastRunUserId.get() : "none");
        
        // Add resource type information with prompts
        ResourceRequirements requirements = getResourceRequirements();
        if (requirements != null) {
            Map<String, Map<String, String>> resourceTypes = new LinkedHashMap<>();
            requirements.getAllResources().forEach((name, resourceConfig) -> {
                Map<String, String> resourceInfo = new LinkedHashMap<>();
                resourceInfo.put("type", resourceConfig.getType().name());
                
                // Add LLM details from the actual AI service annotation
                if (resourceConfig.getType() == io.github.rigazilla.memory.cognition.resource.ResourceType.LLM) {
                    if ("consolidator".equals(name)) {
                        addLlmDetails(resourceInfo, ProfileContextConsolidator.class);
                    }
                    
                    String promptContent = "consolidator".equals(name) ? consolidatorPrompt : null;
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

    /**
     * Trigger consolidation for a specific user.
     * Called by ProfileContextResource.
     */
    public void triggerConsolidation(String userId) {
        LOG.infof("Triggering consolidation for user: %s", userId);
        
        try {
            profileContextService.consolidateProfile(userId);
            lastRunTime.set(Instant.now());
            lastRunStatus.set("success");
            lastRunUserId.set(userId);
            LOG.infof("Consolidation completed successfully for user: %s", userId);
            
        } catch (Exception e) {
            lastRunTime.set(Instant.now());
            lastRunStatus.set("error: " + e.getMessage());
            lastRunUserId.set(userId);
            LOG.errorf(e, "Consolidation failed for user: %s", userId);
            throw e;
        }
    }

    @Override
    public ResourceRequirements getResourceRequirements() {
        // Declare LLM resource for profile consolidation
        // Uses higher temperature (0.3) for more creative consolidation
        return ResourceRequirements.builder()
            .llm("consolidator", createDefaultLlmConfig())
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
                return 0.3;  // Higher temperature for creative consolidation
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
