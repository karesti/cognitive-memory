package io.github.rigazilla.memory.cognition.extraction;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Locale;

/**
 * A candidate memory extracted from conversation evidence.
 * Contains the memory content, type, confidence, and citations.
 * 
 * Uses lenient deserialization to handle LLM responses with empty/invalid candidates.
 * Invalid candidates (empty content, zero confidence, no citations) are filtered out
 * in DurableExtractionResponse.getAllCandidates().
 */
public record MemoryCandidate(
    String type,           // fact, preference, procedure, problem_solution, decision
    String content,        // The actual memory content
    double confidence,     // 0.0-1.0 confidence score
    List<String> citations // References to evidence (entry IDs or text snippets)
) {
    
    /**
     * Lenient factory method for Jackson deserialization.
     * Allows construction of invalid candidates that will be filtered later.
     */
    @JsonCreator
    public static MemoryCandidate create(
        @JsonProperty("type") String type,
        @JsonProperty("content") String content,
        @JsonProperty("confidence") double confidence,
        @JsonProperty("citations") List<String> citations
    ) {
        // Normalize nulls to empty values for lenient parsing
        if (type == null) { type = ""; }
        if (content == null) { content = ""; }
        if (citations == null) { citations = List.of(); }
        
        // Allow construction even if invalid - will be filtered in getAllCandidates()
        return new MemoryCandidate(type, content, confidence, citations);
    }
    
    /**
     * Compact constructor with minimal validation.
     * Only validates type is not null (required for filtering).
     */
    public MemoryCandidate {
        if (type == null) {
            type = "";
        }
        if (content == null) {
            content = "";
        }
        if (citations == null) {
            citations = List.of();
        }
    }
    
    /**
     * Check if this candidate is valid for storage.
     * Used by DurableExtractionResponse.getAllCandidates() to filter out invalid candidates.
     */
    public boolean isValid() {
        return !type.isBlank()
            && !content.isBlank()
            && confidence > 0.0
            && !citations.isEmpty();
    }
    
    @Override
    public String toString() {
        return String.format(Locale.ROOT, "MemoryCandidate{type=%s, confidence=%.2f, " +
                                          "citations=%d, content='%s', " +
                                          "valid=%s}",
            type, confidence, citations.size(),
            content.length() > 50 ? content.substring(0, 47) + "..." : content,
            isValid());
    }
}
