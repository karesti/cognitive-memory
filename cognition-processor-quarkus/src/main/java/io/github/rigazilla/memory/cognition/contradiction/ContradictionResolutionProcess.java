package io.github.rigazilla.memory.cognition.contradiction;

import io.github.rigazilla.memory.cognition.config.CognitionConfig;
import io.github.rigazilla.memory.cognition.process.CognitiveProcess;
import io.github.rigazilla.memory.cognition.process.ManagedProcessInspection;
import io.github.rigazilla.memory.cognition.process.ManagedProcessState;
import io.github.rigazilla.memory.cognition.resource.DefaultLlmResourceConfiguration;
import io.github.rigazilla.memory.cognition.resource.ResourceRequirements;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Managed {@link CognitiveProcess} adapter for contradiction detection and resolution.
 *
 * <p>Registered automatically via CDI and discoverable at
 * {@code GET /api/processes/contradiction-resolution}.
 *
 * <p>Trigger via {@code POST /api/processes/contradiction-resolution/start}.
 * Supports an optional {@code namespacePrefix} parameter to scope the run to a specific
 * user or namespace (same convention as {@code metadata-enrichment}).
 */
@ApplicationScoped
public class ContradictionResolutionProcess implements CognitiveProcess {

    private static final Logger LOG = Logger.getLogger(ContradictionResolutionProcess.class);
    public static final String PROCESS_ID = "contradiction-resolution";

    @Inject
    ContradictionResolutionService resolutionService;

    @Inject
    CognitionConfig cognition;

    // -------------------------------------------------------------------------
    // CognitiveProcess identity
    // -------------------------------------------------------------------------

    @Override
    public String id() {
        return PROCESS_ID;
    }

    @Override
    public String displayName() {
        return "Contradiction Resolution";
    }

    @Override
    public String description() {
        return "Detects contradictory memories and resolves conflicts by marking superseded "
                + "memories with status metadata. Uses LLM-based semantic analysis.";
    }

    // -------------------------------------------------------------------------
    // Capability flags
    // -------------------------------------------------------------------------

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

    // -------------------------------------------------------------------------
    // Start
    // -------------------------------------------------------------------------

    @Override
    public void start() {
        start(Map.of());
    }

    @Override
    public void start(Map<String, List<String>> params) {
        List<String> prefix = params.get("namespacePrefix");
        LOG.infof("Start requested for process %s (namespacePrefix=%s)", id(), prefix);
        resolutionService.startAsync(prefix);
    }

    // -------------------------------------------------------------------------
    // Inspect
    // -------------------------------------------------------------------------

    @Override
    public ManagedProcessInspection inspect() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("status",         resolutionService.getStatus());
        details.put("scanned",        resolutionService.getScanned());
        details.put("pairsChecked",   resolutionService.getPairsChecked());
        details.put("contradictions", resolutionService.getContradictions());
        details.put("resolved",       resolutionService.getResolved());
        details.put("errors",         resolutionService.getErrors());
        details.put("lastRunTime",
                resolutionService.getLastRunTime() != null
                        ? resolutionService.getLastRunTime().toString()
                        : "never");

        ResourceRequirements requirements = getResourceRequirements();
        if (requirements != null) {
            Map<String, Map<String, String>> resourceTypes = new LinkedHashMap<>();
            requirements.getAllResources().forEach((name, resourceConfig) -> {
                Map<String, String> resourceInfo = new LinkedHashMap<>();
                resourceInfo.put("type", resourceConfig.getType().name());
                try {
                    String prompt = new String(
                            getClass().getClassLoader()
                                    .getResourceAsStream("prompts/contradiction-detector-system.md")
                                    .readAllBytes()
                    );
                    resourceInfo.put("prompt", prompt);
                } catch (Exception e) {
                    LOG.warnf("Failed to load contradiction detector prompt: %s", e.getMessage());
                    resourceInfo.put("prompt", "Error loading prompt");
                }
                resourceTypes.put(name, resourceInfo);
            });
            details.put("resourceTypes", resourceTypes);
        }

        return new ManagedProcessInspection(id(), displayName(), description(), state(), details);
    }

    // -------------------------------------------------------------------------
    // Resource requirements
    // -------------------------------------------------------------------------

    @Override
    public ResourceRequirements getResourceRequirements() {
        CognitionConfig.Contradiction.LlmConfig llmCfg = cognition.contradiction().llm();
        return ResourceRequirements.builder()
                .llm("detector", new DefaultLlmResourceConfiguration(
                        llmCfg.provider(),
                        llmCfg.model(),
                        llmCfg.temperature(),
                        llmCfg.maxTokens(),
                        llmCfg.timeout(),
                        Optional.empty(),
                        Map.of()))
                .build();
    }
}
