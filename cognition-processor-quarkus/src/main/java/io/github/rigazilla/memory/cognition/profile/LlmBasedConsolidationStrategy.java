package io.github.rigazilla.memory.cognition.profile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.protobuf.ListValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.github.chirino.memory.grpc.v1.MemoryItem;
import io.github.rigazilla.memory.cognition.resource.LlmRetryHelper;
import io.github.rigazilla.memory.cognition.token.TokenTrackingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * LLM-based implementation of profile consolidation strategy.
 * This is the experimental component that can be modified and improved.
 * 
 * Current approach:
 * 1. Group memories by type
 * 2. Build JSON representation for LLM
 * 3. Invoke LLM consolidator
 * 4. Convert response to ProfileSnapshot
 */
@ApplicationScoped
public class LlmBasedConsolidationStrategy implements ProfileConsolidationStrategy {
    
    private static final Logger LOG = Logger.getLogger(LlmBasedConsolidationStrategy.class);
    
    @Inject
    ProfileContextConsolidator consolidator;

    @Inject
    LlmRetryHelper llmRetryHelper;

    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Override
    public ProfileSnapshot consolidate(List<MemoryItem> memories, String userId) {
        LOG.infof("Consolidating %d memories for user %s", memories.size(), userId);
        
        if (memories.isEmpty()) {
            LOG.warnf("No memories to consolidate for user %s", userId);
            return createEmptySnapshot(userId);
        }
        
        try {
            // Step 1: Group memories by type
            Map<String, List<MemoryItem>> groupedMemories = groupMemoriesByType(memories);
            LOG.debugf("Grouped memories: %s", groupedMemories.keySet());
            
            // Step 2: Build JSON for LLM
            String memoriesJson = buildMemoriesJson(memories);
            LOG.debugf("Built JSON payload: %d characters", memoriesJson.length());
            
            // Step 3: Invoke LLM consolidator — wrapped with retry/backoff
            ProfileConsolidationResponse response = llmRetryHelper.withRetry(
                    "profile-consolidation:" + userId,
                    () -> TokenTrackingContext.call(ProfileContextConsolidationProcess.PROCESS_ID, "consolidator",
                            () -> consolidator.consolidate(memoriesJson)));
            LOG.infof("LLM consolidation complete for user %s", userId);
            
            // Step 4: Convert to ProfileSnapshot
            return buildProfileSnapshot(response, userId);
            
        } catch (Exception e) {
            LOG.errorf(e, "Failed to consolidate profile for user %s", userId);
            throw new ProfileConsolidationException("Profile consolidation failed for user " + userId, e);
        }
    }
    
    /**
     * Group memories by their type field.
     */
    private Map<String, List<MemoryItem>> groupMemoriesByType(List<MemoryItem> memories) {
        return memories.stream()
            .collect(Collectors.groupingBy(this::extractMemoryType));
    }
    
    /**
     * Extract memory type from namespace.
     * Namespace format: ["user", userId, "cognition.v1", type]
     */
    private String extractMemoryType(MemoryItem memory) {
        List<String> namespace = memory.getNamespaceList();
        if (namespace.size() >= 4) {
            return namespace.get(3);
        }
        return "unknown";
    }
    
    /**
     * Build JSON representation of memories for LLM.
     */
    private String buildMemoriesJson(List<MemoryItem> memories) {
        try {
            ArrayNode memoriesArray = objectMapper.createArrayNode();
            
            for (MemoryItem memory : memories) {
                ObjectNode memoryNode = objectMapper.createObjectNode();
                memoryNode.put("key", memory.getKey());
                memoryNode.put("type", extractMemoryType(memory));
                
                // Extract content from value struct
                Struct value = memory.getValue();
                if (value.containsFields("content")) {
                    String content = value.getFieldsOrThrow("content").getStringValue();
                    memoryNode.put("content", content);
                }
                
                // Extract confidence
                if (value.containsFields("confidence")) {
                    double confidence = value.getFieldsOrThrow("confidence").getNumberValue();
                    memoryNode.put("confidence", confidence);
                }
                
                // Extract citations
                if (value.containsFields("citations")) {
                    Value citationsValue = value.getFieldsOrThrow("citations");
                    if (citationsValue.hasListValue()) {
                        ArrayNode citationsArray = objectMapper.createArrayNode();
                        ListValue citationsList = citationsValue.getListValue();
                        for (Value citation : citationsList.getValuesList()) {
                            citationsArray.add(citation.getStringValue());
                        }
                        memoryNode.set("citations", citationsArray);
                    }
                }
                
                memoriesArray.add(memoryNode);
            }
            
            return objectMapper.writeValueAsString(memoriesArray);
            
        } catch (Exception e) {
            LOG.errorf(e, "Failed to build memories JSON");
            throw new RuntimeException("Failed to serialize memories to JSON", e);
        }
    }
    
    /**
     * Convert LLM response to ProfileSnapshot.
     */
    private ProfileSnapshot buildProfileSnapshot(ProfileConsolidationResponse response, String userId) {
        // Build content by concatenating all sections
        StringBuilder contentBuilder = new StringBuilder();
        
        contentBuilder.append("# Profile Snapshot\n\n");
        if (response.profileSnapshot() != null) {
            contentBuilder.append(response.profileSnapshot().content()).append("\n\n");
        }
        
        contentBuilder.append("# Active Goals\n\n");
        if (response.activeGoals() != null) {
            contentBuilder.append(response.activeGoals().content()).append("\n\n");
        }
        
        contentBuilder.append("# Preferences\n\n");
        if (response.preferences() != null) {
            contentBuilder.append(response.preferences().content()).append("\n\n");
        }
        
        // Build sections map
        Map<String, ProfileSnapshot.ProfileSection> sections = new LinkedHashMap<>();
        
        if (response.profileSnapshot() != null) {
            sections.put("profile_snapshot", new ProfileSnapshot.ProfileSection(
                response.profileSnapshot().content(),
                response.profileSnapshot().confidence(),
                response.profileSnapshot().sourceMemoryKeys()
            ));
        }
        
        if (response.activeGoals() != null) {
            sections.put("active_goals", new ProfileSnapshot.ProfileSection(
                response.activeGoals().content(),
                response.activeGoals().confidence(),
                response.activeGoals().sourceMemoryKeys()
            ));
        }
        
        if (response.preferences() != null) {
            sections.put("preferences", new ProfileSnapshot.ProfileSection(
                response.preferences().content(),
                response.preferences().confidence(),
                response.preferences().sourceMemoryKeys()
            ));
        }
        
        return new ProfileSnapshot(
            userId,
            Instant.now(),
            contentBuilder.toString().trim(),
            sections
        );
    }
    
    /**
     * Create an empty snapshot when no memories are available.
     */
    private ProfileSnapshot createEmptySnapshot(String userId) {
        return new ProfileSnapshot(
            userId,
            Instant.now(),
            "No profile information available yet.",
            Map.of()
        );
    }
    
    /**
     * Exception thrown when profile consolidation fails.
     */
    public static class ProfileConsolidationException extends RuntimeException {
        public ProfileConsolidationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
